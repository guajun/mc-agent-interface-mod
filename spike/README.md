# Issue #7: control connections on the Minecraft game port

This directory is the issue #7 spike: can a daemon-shaped, non-player
connection be accepted on the **actual Minecraft game port**, with no second
listening socket, no sidecar daemon, no SSH and no client relay - and can the
mod tell it apart from a normal player without disturbing them?

The answer from this spike is **yes on Minecraft Java 26.2**, for a dedicated
server and for a LAN-hosted integrated server, with the experiment scripts and
evidence below. The control endpoint here is unauthenticated and experimental
by design: authentication and the real wire protocol are
[issue #8](https://github.com/guajun/mc-agent-interface/issues/8), and the plan
this belongs to is [mc-agent#39](https://github.com/guajun/mc-agent/issues/39).

## What the vanilla 26.2 network path actually does

Verified by decompiling the unobfuscated classes in the 26.2 server and client
jars shipped at `D:/MC/MC_Game/.minecraft` (server `26.2` bundle, client
`26.2-Fabric.jar`), not from documentation:

- A dedicated server binds the game port in
  `DedicatedServer.initServer` -> `ServerConnectionListener.startTcpServerListener(InetAddress, int)`.
- An integrated server published to the LAN goes through
  `IntegratedServer.publishServer(scope, port)` ->
  `ServerConnectionListener.startTcpServerListener(null, port)`.
  `IntegratedServer.unpublishServer()` and world close call
  `ServerConnectionListener.stopTcpServerListener()`. So "Open to LAN" is a
  real TCP listener on a real, caller-visible port; there is no special path.
- Each accepted TCP channel is initialized by the anonymous
  `ServerConnectionListener$1.initChannel(Channel)`, which builds, in order:
  `TCP_NODELAY`, a `ReadTimeoutHandler(30s)` named `timeout`, optional
  `legacy_query` (`LegacyQueryHandler`), the framing and codec handlers from
  `Connection.configureSerialization(...)`, a fresh `Connection` (or
  `RateKickingConnection`), a `List.add` into
  `ServerConnectionListener.connections`, `Connection.configurePacketHandler`
  (adds `hackfix` and `packet_handler`, the `Connection` itself) and
  `setListenerForServerboundHandshake`.
- Single player without LAN uses `ServerConnectionListener$2` /
  `startMemoryChannel` and a Netty `LocalChannel`; it has no OS port and cannot
  be reached over TCP at all. That is the documented "local adapter" path, and
  this spike deliberately does not touch it. The pre-existing loopback server
  vantage (`mcagent.serverPort`, 25581) still serves it.

The first byte a vanilla client sends is the length VarInt of the handshake
packet and the second is packet id `0x00` (status or login). Packet id `0x43`
does not exist in the handshake state, which is what makes the marker below
collision-free.

## Candidate access points

| Candidate | Where it runs | Verdict |
| --- | --- | --- |
| Explicit ASCII marker, sniffed at the head of the child pipeline | before any Minecraft decoding | **chosen for the spike** - one handler, zero changes to vanilla code, marker absent means the pipeline is untouched |
| TLS ClientHello detection at the same seam | same | possible later; a ClientHello starts `0x16 0x03 ...`, which the same sniffer could dispatch. Not implemented or tested here |
| Marker inside a valid Minecraft handshake (special hostname/protocol) | after the handshake packet is decoded | more Minecraft protocol to emulate, and control traffic participates in the vanilla handshake state machine; not chosen |
| `SO_REUSEPORT`/second listener on the same port | OS level | not portable/reliable on Windows, and it is still a second listener; rejected |
| External proxy or a second port | outside the game | explicitly out of scope for #7 |

TLS, WebSocket and the final marker format are **not decided** by this spike;
the marker is a temporary probe value.

## How the spike works

`SamePortConnectionMixin` injects at the head of
`Connection.configurePacketHandler` and, only for a `SERVERBOUND` connection
whose channel is not a `LocalChannel`, puts one `ByteToMessageDecoder`
(`SamePortControl.Sniffer`) at the front of the pipeline before anything reads
a byte. Then:

- **Marker present.** The first 18 ASCII bytes are
  `MCAGENT-CONTROL/1` + `\n`. The sniffer consumes the marker, detaches the
  never-handshaked placeholder `Connection` from
  `ServerConnectionListener.connections` (so it is never ticked and never logs
  a player-shaped disconnect), removes the leftover vanilla handlers, and
  becomes the control transport. The existing JSON-lines protocol is then
  served by the same `ServerCore`/`LineHandler` the loopback server vantage
  uses. The hello line adds `"transport":"same-port-spike"` and a session id;
  `STATE` adds a `samePortSpike` object with `enabled`, `allowRemote`,
  `sessions` and the open session list.
- **Marker absent.** The sniffer removes itself and Netty forwards the already
  buffered bytes into the untouched vanilla pipeline. A normal player is
  byte-for-byte unaffected.

Lifecycle:

- `SamePortListenerMixin` remembers the listener that owns the current TCP
  port (`startTcpServerListener`) and closes all control sessions when
  `stopTcpServerListener`/`stop` run - i.e. when "Close LAN"/world close
  tears the port down.
- `SamePortListenerAccessor` exposes `connections` for the detach step.

Safety rails (this is an unauthenticated spike):

- off unless `-Dmcagent.samePortSpike=true` is set explicitly; the server logs
  a loud ENABLED warning;
- off-box control clients are refused unless
  `-Dmcagent.samePortSpikeAllowRemote=true` (loopback-only by default);
- a connection that sends no marker within
  `mcagent.samePortSpikeHandshakeSeconds` (10s) is closed;
- an idle control session is closed after
  `mcagent.samePortSpikeIdleSeconds` (300s);
- a request line longer than `mcagent.samePortSpikeMaxLineBytes` (65536)
  closes the session, and a non-writable socket (slow consumer) closes it
  instead of queueing without bound;
- clean shutdown removes the session from the registry and closes the channel.

## Version coupling (recorded, not hidden)

The spike compiles and runs against Minecraft 26.2 (unobfuscated) only:

| Coupled element | 26.2 fact |
| --- | --- |
| `net.minecraft.network.Connection#configurePacketHandler(ChannelPipeline)` | arming point; also reads the `receiving` field |
| `PacketFlow.SERVERBOUND`, `io.netty.channel.local.LocalChannel` | filter that keeps the sniffer off outbound and in-memory connections |
| `net.minecraft.server.network.ServerConnectionListener` | `startTcpServerListener`, `stopTcpServerListener`, `stop`, `connections` |
| handler names `timeout`, `splitter`, `decoder`, `prepender`, `encoder`, `outbound_config`, `hackfix`, `packet_handler` | removed at takeover; unknown future handlers are removed generically except Netty's hidden head/tail contexts |
| `MCAGENT-CONTROL/1` + `\n` | provisional marker; a vanilla handshake cannot collide (packet id `0x43` is not a handshake packet) |

On a different Minecraft version the mixins may fail to apply, and because the
mixin config is `required: true` that would be visible at startup rather than
silently degrading. That is the intended behaviour for a spike pinned to a
known game version.

## Experiments and results

Driver: `spike/same_port_spike.py` (stdlib only). It provisions a throwaway
`tools/lab_server.py` lab, runs the Go probe
(`spike/probe/main.go`, stdlib only) twice as independent processes, drives a
real Minecraft client through the mod's own `CONNECT`/`WORLD`/`LAN` requests,
and writes `summary.json` per run. The full check list and probe metrics are
committed in `spike/evidence/issue-7-evidence.json`.

Environment: Minecraft 26.2, Fabric loader 0.19.5, Fabric API 0.161.0+26.2,
Java 25.0.1 (Microsoft), Windows, mod jar
`dist/mc-agent-interface-0.7.0.jar`
(sha256 `c78a85ddb26bbbedd0a0627046af77d8796dbd2b67b37cab4ed0f395048edb54`),
probe built with Go 1.26.4. A rebuild is source-identical but not
byte-identical (`build.py` does not normalise ZIP entry timestamps), so the
hash identifies the artifact of the recorded run.

### Dedicated server - 26/26 checks passed

- Vanilla status ping and ping/pong on the game port succeed with the marker
  sniffer armed (protocol 776, server 26.2).
- Two independent Go probes connect to the game port with the marker; both get
  the spike hello, `PING`, `STATE`, hold for 90s, `MARK`, then half-close and
  see the server close its side.
- `STATE` from probe A shows `control_sessions` going 1 -> 2 while probe B is
  connected, then back down, and both probes see the other session - they are
  concurrent and independent.
- A real Minecraft client (offline identity `issue7-player`) joins the same
  server, is visible in the control connections' `STATE` player list and in
  RCON `list`, quits (`lost connection: Disconnected`), reconnects, and quits
  again - all while both control sessions stay connected and continue
  answering `STATE`.
- No control session ever appears in the player list, a join/leave line or an
  entity list; RCON `list` is empty before the probes, during gameplay and
  after everything closes.

Key server log excerpt (control sessions bracketing two real player sessions):

```
same-port control spike ENABLED (experimental, unauthenticated) ...
same-port control session spike-1 accepted ... sessions=1
same-port control session spike-2 accepted ... sessions=2
issue7-player joined the game
issue7-player lost connection: Disconnected
issue7-player left the game
issue7-player joined the game
issue7-player lost connection: Disconnected
issue7-player left the game
same-port control session spike-1 ... closed: channel closed by peer; sessions=0
same-port control session spike-2 ... closed: channel closed by peer; sessions=1
```

### LAN-hosted integrated server - 11/11 checks passed

- A real client opens a copied throwaway world in an isolated game directory
  and publishes it to the LAN on an explicit port. The probe connects to the
  **actual** published port (25565), gets hello/`PING`/`STATE`, and the host
  player appears in the control `STATE` player list.
- Closing the world (killing the host client) drops the control connection;
  the port stops listening - the probe records a clean disconnect rather than
  hanging.
- Reopening the world and publishing to LAN on a different port (25566) makes
  a new probe connect to that new port; a control connection can also close
  itself cleanly while the world stays open.

### Reproduce

```bash
# 1. build the mod (Java 25)
python build.py --minecraft-dir "D:/MC/MC_Game/.minecraft" --version 26.2-Fabric --jdk "<jdk25>"

# 2. build the Go probe
cd spike/probe && go build -o ../../dist/control-probe .

# 3. dedicated server + two probes + real client join/quit/reconnect
python spike/same_port_spike.py dedicated \
    --lab-server <meta>/tools/lab_server.py \
    --lab-root <workdir>/labs \
    --mod-jar dist/mc-agent-interface-0.7.0.jar \
    --probe dist/control-probe.exe \
    --jdk25 "<jdk25>/bin/java.exe" \
    --meta-root <meta> --minecraft-dir "D:/MC/MC_Game/.minecraft" \
    --game-dir <workdir>/client-dedicated --client \
    --evidence-dir <workdir>/evidence-dedicated

# 4. LAN integrated server, close on 25565, reopen on 25566
python spike/same_port_spike.py lan \
    ... --game-dir <workdir>/client-lan --lan-port 25565 \
    --evidence-dir <workdir>/evidence-lan
```

The raw `RESULT` lines from the probe are JSON and can be checked without the
driver:

```bash
python spike/probe/main.go ...   # or: go run spike/probe -addr host:port -name A -hold 20s
python spike/mc_ping.py --host 127.0.0.1 --port 25565
```

## Honest limitations

- **Not tested with an external proxy.** A transparent TCP forwarder would
  forward the marker bytes unchanged and should work, but no proxy was run in
  this spike. A proxy that terminates and understands the Minecraft protocol
  (e.g. Velocity/BungeeCord-style) may reject or mangle the non-Minecraft
  marker. No compatibility is claimed for any proxy; the support matrix is a
  #8 deliverable.
- **The spike endpoint is unauthenticated.** That is deliberate for #7; it must
  only be enabled in isolated tests, and it refuses non-loopback clients by
  default. Authentication, credentials and the formal protocol belong to #8.
- **Only Minecraft 26.2 was exercised**, only on Windows, and only with one
  game process at a time. A remote LAN daemon (another machine) was not used:
  the LAN run dials the LAN port over loopback. Remote control is gated behind
  `mcagent.samePortSpikeAllowRemote` and untested there.
- **No TLS / no formal version preamble** is implemented; only the temporary
  ASCII marker is verified.
- The pre-existing loopback server vantage (`mcagent.serverPort`) still starts;
  the spike does not remove it. The same-port transport does not depend on it.
- Single-player-without-LAN has no TCP port by design; the path there remains
  the local loopback adapter.

## Relation to #8 and #39

#8 should take the seam and constraints from this spike - the channel-head
sniffer, the detach of the placeholder connection, the close/timeout budgets -
and replace the provisional marker with the authenticated, versioned protocol.
#39's other plan items (Go CLI/daemon, MCP removal, distribution) are out of
scope for this pull request.
