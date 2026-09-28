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
credential, the mod issues one `read+write` bootstrap credential and writes it
to `<gameDir>/mc-agent-server/control/bootstrap-token.txt` (owner-only); the
normal log only points at that file. Move the secret to the daemon operator and
delete the file; revoke the bootstrap credential after issuing per-daemon
credentials.

A token carries `read` and/or `write`. Read covers state, entities, player
context, chat context bundles, snapshots and the event stream; write covers
`command`, `mark` and `snapshot`. Events are read data: a write-only credential
receives no pushed events and no replay. Revocation, expiry and permission
changes are checked against the live store before every operation, a queued
write that is revoked before the game thread claims it is cancelled instead of
running, and a running write that is cancelled by expiry is closed while its
terminal outcome stays recorded. Chat identity is never an authorization:
permissions come from the credential.

**Credential administration is console-only.** A remote `write` credential may
run ordinary game commands at ADMIN level, but the `/mcagent control token ...`
subtree requires OWNER, so direct calls and `/execute`/`/function` chains
cannot mint or revoke credentials. The local legacy loopback adapter keeps the
owner-level command source because it is already bound to the server machine.

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
 "lastSeq":42,"runId":"run_...","client":{"name":"mc-agent","version":"0.5.0"}}
```

`runId` is optional and scopes the cursor: it names the run `lastSeq` belongs
to. When it differs from the server's run, the cursor is not used and the
replay is reported as a cross-run gap (see below), so a reconnecting daemon
can never present a previous run's high-water mark to a restarted game and
silently skip the new run's events.

The server answers with `welcome` (or a fatal `error`, then closes):

```json
{"type":"welcome","protocol":1,"mod":"mc-agent-interface","modVersion":"0.8.0",
 "minecraft":"26.2","instanceId":"inst_...","runId":"run_...","runStartedAtMillis":0,
 "sessionId":"sess_...","transport":"same-port-tls","serverTimeMillis":0,
 "permissions":["read","write"],
 "capabilities":["state","entities","player","player:view","command","context",
                 "wait","mark","snapshot","events:game","events:chat"],
 `replay":{"requestedSince":42,"from":43,"to":57,"lost":false,
           "bufferedEvents":15,"persistedAcrossRuns":false,"crossRun":false},
 "limits":{"maxFrameBytes":16777216,"maxPendingRequests":32,
           "requestTimeoutMillis":30000,"idleSeconds":300,
           "handshakeSeconds":10,"eventBuffer":1024,"maxDroppedEvents":4096,
           "controlProtocol":1}}
```

`instanceId` is stable across restarts of the same server directory; `runId`
changes on every server start and when an integrated server halts (a reopened
single-player world is a new run). `replay.lost=true` means events older than
the in-memory buffer cannot be replayed; `replay.crossRun=true` means the
client's cursor belonged to another run. Events are never persisted across
restarts and that is reported, not hidden.

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
`snapshots`, `request_status`, and the `exclusive_*` leases. `snapshot` names
are single safe directory segments: absolute paths, `..`, separators, reserved
device names and symlinked directories are rejected before anything is written,
and the resolved directory is proven to stay inside the instance's own
`snapshots/` root. Composite `fork`/`restore`/`freeze`/`verify`/`order`
operations are **not** implemented over this transport: they are refused with
`capability_not_supported` and a reason, because a complete freeze/snapshot/fork
semantics has not been designed yet (mc-agent#39), and remote world paths must
never be treated as local paths.

Timeout semantics are explicit. A client that gives up sends nothing further
for that id; a `timeout` reply carries `retryable` and `resultUnknown`.
The queued/running/cancelled transition is one atomic step: whichever side
wins, the other observes it. A request that has not been claimed by the game
thread is cancelled (the queued game task sees the cancelled state and does
not run), the ledger entry is dropped, and the client may safely retry. A request that already started
reports `resultUnknown=true` and keeps its pending ledger record until the
game thread finishes, so `request_status` can resolve it later. A running
request still counts against the session's pending budget after a timeout, so
a slow client cannot pile unbounded work onto the game thread.

### Writes, ordering and result-unknown recovery

Duplicate in-flight request ids are rejected before any ledger reservation,
for reads and writes alike, so a second request can never overwrite a live
one. Writes are `command`, `mark` and `snapshot`. Each write id is reserved
atomically before anything else happens:

- the first use of an id creates a `pending` record;
- the same id with a different payload is `conflict` and is never executed;
- the same id with the same payload while running is `request_in_flight`;
- the same id with the same payload after completion is the stored result with
  `"duplicate":true`;
- pending records are never evicted; a full ledger returns `server_busy`
  instead of dropping a status.

The server assigns each completed write a monotonic `writeSeq`, returns it in
the reply result, and publishes a sequenced `write` event:

```json
{"type":"event","seq":12,"runId":"run_...","streamId":"run_...",
 "event":{"type":"write","writeSeq":4,"op":"command","requestId":"c1",
          "tokenId":"tk_...","ok":true,"millis":0}}
```

Replies are de-duplicated per `(token id, request id)` as described above.
`request_status` with `{"requestId":"c1"}` answers
`{"requestId":"c1","state":"pending|completed|failed|unknown",...}` from a
bounded (1024 entries, 30 minutes) server-side ledger. A write whose socket
disconnects after it started keeps running and records its terminal outcome,
so a daemon can resolve it after reconnecting. The ledger covers the current
run only; after a game restart `state` is `unknown` and a blind retry is not
safe. `request_status` needs `read` or `write` for the same credential (it only
reveals its own requests).

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
`lastSeq` (with the run it belongs to); the server replays buffered envelopes
from `lastSeq+1`, marking each with `"replay":true`. Replay, the welcome frame
and the switch to live delivery happen under one lock, so a live event is never
reordered before or lost behind a replayed one. If the buffer no longer reaches
back, `welcome.replay` reports `lost:true` with the `from`/`to` boundaries; a
cursor from another run is reported with `crossRun:true`. A session whose
reader cannot drain the outbound queue is closed rather than silently dropping
events, so the next reconnect always produces a machine-readable replay report.

## Limits and safety budgets

| Property | Default | Purpose |
| --- | --- | --- |
| `mcagent.controlHandshakeSeconds` | 10 | TLS + hello deadline |
| `mcagent.controlIdleSeconds` | 300 | read-idle timeout |
| `mcagent.controlMaxFrameBytes` | 16 MiB | frame size |
| `mcagent.controlMaxPending` | 32 | in-flight requests per session |
| `mcagent.controlRequestTimeoutMillis` | 30000 | default operation deadline |
| `mcagent.controlEventBuffer` | 1024 | replay ring size |
| `mcagent.controlMaxDroppedEvents` | 4096 | retained for diagnostics; a session that cannot drain is closed |
| `mcagent.controlMaxOutboundBytes` | 4 MiB | byte budget covering frames queued but not yet written |
| `mcagent.controlMinRequestTimeoutMillis` | 1000 | floor for a per-request `timeoutMillis` |

Replies and events share one outbound byte budget that also covers frames
scheduled on the event loop but not yet written, so a stalled loop or reader
cannot accumulate unbounded buffers; exceeding the budget or a frame limit
closes the session or turns an oversized reply into a structured
`response_too_large` error. The game thread is never blocked by a slow reader.

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
