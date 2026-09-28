# Control protocol 1 on the Minecraft game port

This document is the contract between `mc-agent-interface-mod` (0.8.0+) and a
control daemon. It covers the authenticated transport added for
[interface-mod#8](https://github.com/guajun/mc-agent-interface-mod/issues/8) and
the write-coordination rules from
[mc-agent#6](https://github.com/guajun/mc-agent/issues/6). The plaintext JSON
lines protocol on `127.0.0.1:25581` remains a separate, explicit legacy
adapter.

## Transport and port sharing

The mod listens on the **actual Minecraft game port** - the same TCP listener
that accepts players, for both a dedicated server and a LAN-published
integrated server. A single `ByteToMessageDecoder` at the head of each accepted
server connection peeks at the first two bytes:

| First bytes | Decision |
| --- | --- |
| `0x16 0x03` (a TLS record header) | This is a control candidate: the vanilla handlers are removed, the placeholder player `Connection` is detached from the server's connection list, and a Netty `SslHandler` plus the control session take over. No player, entity or join/leave is ever created. |
| anything else | The bytes are handed to the untouched vanilla pipeline. A Minecraft handshake cannot start `0x16 0x03` because the second byte of a handshake is packet id `0`, so player traffic is never delayed beyond two bytes. |

Only TLS 1.2 and 1.3 are offered. On first run the mod generates its own
identity with the JDK `keytool` (EC P-256, self-signed, SANs for `localhost`,
`127.0.0.1`, the host name and the machine's addresses). Operators can supply a
PKCS#12 keystore (`-Dmcagent.controlKeystore` + `-Dmcagent.controlStorePassword`)
or a PEM pair (`-Dmcagent.controlCertificate` + `-Dmcagent.controlPrivateKey`)
instead. The public certificate is written to
`<gameDir>/mc-agent-server/control/server.crt` and its SHA-256 fingerprint to
`fingerprint.txt` **only after the TLS context is armed**.

A client must verify the server: either pin the SHA-256 fingerprint, or load
`server.crt` as the only trust root (a CA file). There is no trust-all mode and
no way to disable verification.

`mcagent.control=true` enables the transport. It is off by default.

## Credentials

Administration happens on the server console (permission level 3+):

```
/mcagent control status
/mcagent control token add <label> read|write|read+write [ttlSeconds]
/mcagent control token list
/mcagent control token revoke <id>
/mcagent control reload
```

Credentials are stored as salted SHA-256 hashes in
`<gameDir>/mc-agent-server/control/tokens.json`; the secret is shown exactly
once in the form `mca1.<id>.<base64url>`. On a server that has no usable
credential, the mod issues one `read+write` bootstrap credential and logs it
once with a loud line - revoke it after issuing per-daemon credentials.

A token carries `read` and/or `write`. Read covers state, entities, player
context, chat context bundles, snapshots and `request_status`; write covers
`command`, `chat`/`mark` where the vantage supports them, and `snapshot`.
Revocation closes every live session that uses the credential and releases its
exclusive leases; rotation is "issue the replacement, update the daemon,
revoke the old one". Chat identity is never an authorization: permissions come
from the credential.

## Framing

After the TLS handshake the connection is a sequence of frames:

```
uint32 big-endian payload length | payload (UTF-8 JSON object)
```

`1 <= length <= mcagent.controlMaxFrameBytes` (default 16 MiB). A violation is
fatal: the server sends `{"type":"error","code":"frame_too_large"}` and closes.

## Handshake

The first client frame is:

```json
{"type":"hello","protocol":1,"token":"mca1.tok_ab12.<secret>",
 "lastSeq":42,"client":{"name":"mc-agent","version":"0.5.0"}}
```

The server answers with `welcome` (or a fatal `error`, then closes):

```json
{"type":"welcome","protocol":1,"mod":"mc-agent-interface","modVersion":"0.8.0",
 "minecraft":"26.2","instanceId":"inst_...","runId":"run_...","runStartedAtMillis":0,
 "sessionId":"sess_...","transport":"same-port-tls","serverTimeMillis":0,
 "permissions":["read","write"],
 "capabilities":["state","entities","player","player:view","command","context",
                 "wait","mark","snapshot","events:game","events:chat"],
 "replay":{"requestedSince":42,"from":43,"to":57,"lost":false,
           "bufferedEvents":15,"persistedAcrossRuns":false},
 "limits":{"maxFrameBytes":16777216,"maxPendingRequests":32,
           "requestTimeoutMillis":30000,"idleSeconds":300,
           "handshakeSeconds":10,"eventBuffer":1024,"maxDroppedEvents":4096,
           "controlProtocol":1}}
```

`instanceId` is stable across restarts of the same server directory; `runId`
changes on every server start. `replay.lost=true` means events older than the
in-memory buffer (or from a previous run) cannot be replayed. Events are never
persisted across restarts and that is reported, not hidden.

## Requests, replies and errors

```json
{"type":"request","id":"<client-chosen, 1..128 chars>","op":"state","params":{}}
```

```json
{"type":"reply","ok":true,"id":"...","result":{...},"serverTimeMillis":0}
{"type":"reply","ok":false,"id":"...","error":{"code":"forbidden","message":"...",
 "retryable":false,"resultUnknown":false,"details":{...}}}
```

Error codes include `bad_request`, `unauthorized`, `forbidden`,
`capability_not_supported`, `game_error`, `timeout`, `request_in_flight`,
`too_many_pending`, `frame_too_large`, `protocol_version`, `internal`,
`conflict`, `not_found`.

Supported server-vantage operations are `ping`, `capabilities`, `state`,
`entities`, `player`, `context`, `command`, `mark`, `wait`, `snapshot`,
`snapshots`, `request_status`, and the `exclusive_*` leases. Composite
`fork`/`restore`/`freeze`/`verify`/`order` operations are **not** implemented
over this transport: they are refused with `capability_not_supported` and a
reason, because a complete freeze/snapshot/fork semantics has not been designed
yet (mc-agent#39), and remote world paths must never be treated as local
paths.

Timeout semantics are explicit. A client that gives up sends nothing further
for that id; a `timeout` reply carries `retryable` and `resultUnknown`.
If the game operation had not started, `retryable=true, resultUnknown=false`
and a retry is safe. If it was running, `resultUnknown=true` and the client
must **not** blindly replay it.

### Writes, ordering and result-unknown recovery

Writes are `command`, `chat`, `mark` and `snapshot`. The server assigns each
completed write a monotonic `writeSeq`, returns it in the reply result, and
publishes a sequenced `write` event:

```json
{"type":"event","seq":12,"runId":"run_...","streamId":"run_...",
 "event":{"type":"write","writeSeq":4,"op":"command","requestId":"c1",
          "tokenId":"tk_...","ok":true,"millis":0}}
```

Replies are de-duplicated per `(token id, request id)`: resending the same id
returns the stored result with `"duplicate":true` instead of executing again.
`request_status` with `{"requestId":"c1"}` answers
`{"requestId":"c1","state":"pending|completed|failed|unknown",...}` from a
bounded (1024 entries, 30 minutes) server-side ledger, so a daemon that
reconnects can resolve a write whose reply was lost. The ledger covers the
current run only; after a game restart `state` is `unknown` and a blind retry
is not safe.

### Exclusive leases (mc-agent#6)

For future composite operations the server provides a minimal lease:

```
exclusive_acquire {key, ttlSeconds<=3600, label?}
exclusive_renew   {key, ttlSeconds}
exclusive_release {key}
exclusive_status  {key?}
```

A lease belongs to the credential that acquired it, survives a socket
disconnect until its TTL expires (a vanished client never silently releases a
lease another operation may depend on), and can only be renewed/released by
its owner. A second credential receives `conflict` with the holder's JSON in
`error.details`. Leases are dropped on game restart and when the owning
credential is revoked.

## Events

Every pushed event is a server-sequenced envelope:

```json
{"type":"event","seq":13,"runId":"run_...","streamId":"run_...",
 "serverTimeMillis":0,"event":{"type":"chat","event":true,...}}
```

`seq` is monotonic within a run. On reconnect the client sends its last seen
`lastSeq`; the server replays buffered envelopes from `lastSeq+1`, marking each
with `"replay":true`. If the buffer no longer reaches back, `welcome.replay`
reports `lost:true` with the `from`/`to` boundaries. The default buffer holds
1024 events; a slow consumer that cannot keep up has events dropped (counted
and visible in `STATE.control`) but can always recover by reconnecting with its
last complete sequence and reading the gap report.

## Limits and safety budgets

| Property | Default | Purpose |
| --- | --- | --- |
| `mcagent.controlHandshakeSeconds` | 10 | TLS + hello deadline |
| `mcagent.controlIdleSeconds` | 300 | read-idle timeout |
| `mcagent.controlMaxFrameBytes` | 16 MiB | frame size |
| `mcagent.controlMaxPending` | 32 | in-flight requests per session |
| `mcagent.controlRequestTimeoutMillis` | 30000 | default operation deadline |
| `mcagent.controlEventBuffer` | 1024 | replay ring size |
| `mcagent.controlMaxDroppedEvents` | 4096 | slow-consumer drop bound |

Game operations are always executed on the server thread; the network side
only frames, authenticates and queues.

## Compatibility and support matrix

- Player status/login and real players on the same port: verified on Minecraft
  Java 26.2 dedicated and LAN-hosted integrated servers (issue #7/#8 evidence).
- Two independent daemons on one server: verified; replies and events are
  per-connection.
- LAN daemon reachable over the LAN: verified from WSL against the host's
  address (a genuinely separate network stack).
- **Proxy support**: a transparent TCP forwarder passes the TLS bytes
  unchanged and should work, but no proxy was part of this test round. A proxy
  that terminates and interprets the Minecraft protocol
  (Velocity/BungeeCord-style) may reject or mangle the connection, so no proxy
  compatibility is claimed.
- Only Minecraft 26.2 was exercised; the mixin config is `required: true`, so
  an unsupported version fails loudly at startup instead of degrading.

## Cross-language sample

`protocol/fixtures/control-protocol-1.json` contains canonical
hello/welcome/reply/error/event frames. The Go daemon
(`guajun/mc-agent-bridge`, `internal/control`) and the Java server
(`ControlSession`) are the two implementations exercised end to end; the
fixtures exist so a third implementation can be checked without reading either
codebase.
