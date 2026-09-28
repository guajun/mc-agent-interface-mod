# mc-agent-interface

馃摉 Part of **mc-agent**; the guide lives at <https://guajun.github.io/mc-agent/>.

Generic Fabric mod that exposes a local, versioned interface for agent runtimes.
One jar, two entrypoints: a **client vantage** and a **server vantage**.

This module is the **game adapter**. It contains no agent logic and no
use-case logic (no cannon, sulfur cube, pathfinding, or analysis code). It only
answers questions about the game and performs explicit actions requested by a
client.

## Protocol v1

The mod listens on `127.0.0.1:25580` by default (it tries the next free port if
the port is taken) and writes the actual port to `<gameDir>/mc-agent/port.txt`.
Messages are UTF-8 text, one JSON object per line.

On connect the mod sends:

```json
{"type":"hello","mod":"mc-agent-interface","version":"0.7.0","protocol":1,
 "minecraft":"26.2","port":25580,
 "capabilities":["state","entities","command","chat","record","wait","screen",
                  "mark","connect","events:chat","events:game"]}
```

Requests are single lines. The legacy line syntax is intentionally simple and
stable:

| Request | Meaning |
| --- | --- |
| `STATE` | player position/rotation/health/dimension, entity count |
| `ENTITIES [radius]` | entity snapshot (optionally limited by radius) |
| `CMD <command>` | run a server command as the local player |
| `CHAT <text>` | send a chat message |
| `SAMPLE_START <ticks> <radius> [interval]` | start per-tick entity recording |
| `SAMPLE_STOP` | stop recording |
| `WAIT <ticks>` | respond after N client ticks (useful for scripted experiments) |
| `SCREEN` | current screen/class (diagnostics) |
| `CONNECT <host:port>` | connect the client to a server |
| `WORLD <level>` | open a single-player save by folder name |
| `LAN [port] [online\|offline]` | publish the integrated server to the LAN |
| `MARK <text>` | write a marker into the event stream |
| `CAPS` | protocol version and capabilities |
| `PING` | liveness check |

`COMMAND`, `SAY`, `STATE_GET`, `ENTITY_LIST`, `RECORD_START` and `RECORD_STOP`
are accepted as aliases so a bridge can use readable names.

Events are pushed to every connected client and appended to
`<gameDir>/mc-agent/events.jsonl`:

```json
{"type":"chat","millis":...,"event":true,"text":"hello","sender":"LiteralComponent{content='name',...}"}
{"type":"game","millis":...,"event":true,"text":"..."}
{"type":"sample_start","millis":...,"event":true,"text":"ticks=600 radius=32 interval=1"}
```

`"event": true` marks a pushed event. Replies to requests never carry it, so a
client can route lines without keeping a list of every event name - which is
what the bridge does (a new event type used to be mistaken for a reply and
shift every later answer by one).

`text` is the message content; `sender` is the stringified display-name
component, so clients should extract the name defensively rather than assume a
bare player name.

### Chat context bundles (server vantage)

The server vantage does not just broadcast chat text. A mixin captures the
sender's context where the server thread decodes the chat message, before the
asynchronous chat filter and before any socket client or agent can delay
handling, and parks it under the message identity. When the broadcast event
arrives (even seconds later, after filtering) it consumes that receipt, so the
bundle always describes the sender at packet receipt. The event carries an
opaque id plus a compact summary, and every bundle says whether it was frozen
at `"timing":"receipt"` or, for a broadcast that had no packet receipt, a
labelled `"timing":"broadcast"` fallback:

```json
{"type":"chat","millis":...,"event":true,"seq":12,"tick":8451,
 "text":"hello","sender":"Notch","context_id":"ctx-2b1d...",
 "context":{"schema":"player-context/1","uuid":"069a79f4-...","name":"Notch",
            "tick":8451,"timing":"receipt","dimension":"minecraft:overworld",
            "x":10.5,"y":64.0,"z":-3.25,"yaw":180.0,"pitch":12.5,
            "view":{"type":"block"}}}
```

Chat snapshots and live `PLAYER` queries share the same target selection,
including the active item's attack range and the ordinary block/entity pick.

The bundle behind the id is the event-time context plus the full view ray - the
server-side pick along the sender's eye line, computed from authoritative
state, never from a client crosshair:

```json
{"view":{"type":"block","distance":3.1,"block":"minecraft:stone",
         "pos":[10,63,0],"face":"north","hit":[10.5,63.5,-0.25]}}
```

The server vantage answers `CONTEXT` requests for it:

| Request | Meaning |
| --- | --- |
| `CONTEXT <context_id>` | fetch the captured bundle by id |
| `CONTEXT` | cache stats: capacity, ttlMillis, size, hits, misses, expired, evicted |

```json
{"type":"context","millis":...,"status":"ok","context_id":"ctx-2b1d...",
 "ageMillis":842,"cache":{"capacity":256,"ttlMillis":300000,"size":3},
 "context":{"schema":"player-context/1","context_id":"ctx-2b1d...","seq":12,
            "capturedAt":1790343000000,"tick":8451,"timing":"receipt","uuid":"069a79f4-...",
            "name":"Notch","dimension":"minecraft:overworld","x":10.5,"y":64.0,
            "z":-3.25,"yaw":180.0,"pitch":12.5,
            "view":{"type":"block","distance":3.1,"block":"minecraft:stone",
                    "pos":[10,63,0],"face":"north","hit":[10.5,63.5,-0.25]}}}
```

Unknown and expired ids are structured and never return a different player's
context:

```json
{"type":"context","status":"not_found","context_id":"ctx-nope","cache":{...}}
{"type":"context","status":"expired","context_id":"ctx-2b1d...","capturedAt":1790343000000,
 "ageMillis":4200000,"cache":{...}}
```

The cache holds at most 256 bundles for 300 seconds by default. When it is full
the oldest bundle is evicted first; a lookup older than the TTL answers
`expired` once and `not_found` afterwards, and an expired entry is dropped
rather than served. Both limits are system properties (see Configuration).
Receipt captures that never reach a broadcast are held for at most 60 seconds
and use the same bound; only a bundle that was actually broadcast enters the
cache, so junk packets cannot evict live context. If filtering outlives the
receipt TTL, the event reports a structured unavailable context
(`"reason":"receipt_expired"`) instead of silently substituting the later
transform. Bundles contain server-known
values only - identity, transform, one view ray, schema string - never an
entity snapshot or a world save, so the chat event stays small. The server
`CAPS` advertises `"context"` and `"events:chat"` for this.

Entity records for players carry `name` (and `gameMode`), because a client
cannot otherwise tell one player from another - and a Carpet fake player is
just a player entity as far as the client is concerned.

Per-tick samples are appended to `<gameDir>/mc-agent/samples.jsonl`.

## Server vantage

When it runs inside a server - a dedicated one or the integrated server of a
single-player world - the mod also listens on `mcagent.serverPort` (`25581` by
default, falling back to the next free port) and writes the actual port to
`<mcagent.serverDir>/port.txt` (`mc-agent-server/` by default). Its `hello` says
`"instance":"server"`, and its data is authoritative: requests are answered on
the server thread.

The server vantage speaks the same JSON-lines protocol as the client one, with
the shared `STATE`, `ENTITIES`, `CMD`, `WAIT`, `MARK`, `CAPS` and `PING`
requests plus:

| Request | Meaning |
| --- | --- |
| `PLAYER <uuid\|name>` | one online player's server-known context and view target |
| `SNAPSHOT [radius] [name]` / `SNAPSHOTS` | fork/list a live world (snapshot protocol) |

### `PLAYER`

`PLAYER` resolves one online player by stable UUID first (dashes optional) and
by name as a convenience; `matchedBy` says which was used. The reply is the
player's server-known state at request time:

```json
{"type":"player","instance":"server","protocol":1,"modVersion":"0.7.0",
 "tick":104233,"query":"069a79f4-44e9-4726-a5be-fca90e38aaf5","found":true,
 "matchedBy":"uuid",
 "player":{"id":123,"uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5",
   "type":"minecraft:player","name":"Notch","x":10.5,"y":64.0,"z":-3.25,
   "vx":0.0,"vy":-0.0784,"vz":0.0,"yaw":180.0,"pitch":12.5,
   "health":20.0,"maxHealth":20.0,"gameMode":"survival",
   "dimension":"minecraft:overworld","eye":[10.5,65.62,-3.25],"onGround":true},
 "view":{"eye":[10.5,65.62,-3.25],"direction":[0.0,0.216,0.976],
   "blockRange":4.5,"entityRange":3.0,
   "target":{"type":"block","hit":[10.5,65.5,-0.25],"distance":3.1,
     "block":{"x":10,"y":65,"z":0,"id":"minecraft:stone","name":"Stone",
       "face":"north","inside":false}}}}
```

The `player` object is the same record `ENTITIES` gives for a player (identity,
position, velocity, rotation, health, game mode), with `dimension` and the eye
position added. `view` adds the ray and its target, where `view.target.type` is
one of:

- `block` - carries `block.{x,y,z,id,name,face,inside}`;
- `entity` - carries the same record `ENTITIES` gives, under `entity`;
- `miss` - carries only `hit` and `distance`.

The target is a server-side raycast from the player's eye position along the
player's server-known look vector, run on the server thread when the request is
handled. It has the same two stages as the vanilla 26.2 pick: an item carrying
an attack range (the spears) selects first, then the ordinary block/entity pick
with the player's interaction ranges is the fallback, with an out-of-reach hit
becoming a miss. No client state is read: no crosshair, camera, screen or GUI.
The same request therefore works with a dedicated server and with the
integrated server of a single-player world.

An unknown player is a structured answer, not an error and not a dropped
connection:

```json
{"type":"player","instance":"server","found":false,
 "query":"ghost","error":"no online player matches ghost"}
```

Server `CAPS` advertises `"player"` (resolve one player and return their
context) and `"player:view"` (the server-side view-target raycast). The socket
still binds to loopback only, so a co-located Toolkit reaches it exactly like
the client socket and nothing becomes public.

## Same-port control spike (issue #7, experimental)

The server vantage normally waits on its own loopback port (`mcagent.serverPort`).
Issue #7 asks a harder question first: can a daemon-shaped, non-player
connection be accepted on the **actual game port** itself, with no second
listening socket, no sidecar daemon and no client relay? This build contains an
explicitly opt-in spike that says yes on Minecraft 26.2; the reproducible
experiments and results live in `spike/`.

How it works:

- a mixin arms one byte-sniffing handler at the head of every real TCP
  serverbound connection, before Minecraft decodes anything;
- a control connection starts with the ASCII marker
  `MCAGENT-CONTROL/1` followed by a newline (provisional, not the #8 protocol);
- when the marker is present, the handler removes the vanilla pipeline, detaches
  the placeholder `Connection` from the server's connection list, and speaks the
  existing JSON-lines protocol through the same server vantage - no player, no
  entity, no join;
- when it is absent, the handler decides on the first byte that cannot be the
  marker, removes itself, and replays every buffered byte into the untouched
  vanilla pipeline, so even a short vanilla handshake is forwarded at once;
- same-port sessions receive the same pushed events as the loopback vantage, so
  the advertised `events:chat`/`events:game` capability is real.

Enable it only in an isolated test environment:

```
-Dmcagent.samePortSpike=true
```

It accepts unauthenticated control connections on the game port, so off-box
control clients are refused unless `-Dmcagent.samePortSpikeAllowRemote=true` is
also set. Authentication and the real wire protocol are issue #8; TLS and a
formal version preamble are deliberately not decided here.

The transport is verified on a dedicated server (two independent Go probes,
plus a real client joining, quitting and reconnecting, and a graceful server
stop) and on a LAN-hosted integrated server (terminating the host process
releases the port; restarting the host and publishing on a new port connects
again; an in-game world close is not driven by the harness). See
`spike/README.md` for the design, the exact commands, the check-by-check
results and the remaining gaps.

## Formal control protocol (issue #8)

The product transport is the **authenticated TLS control protocol on the game
port**, not the spike marker. It is off unless `-Dmcagent.control=true` is set,
terminates TLS with a per-instance identity generated by the JDK `keytool`,
and authenticates daemons with revocable `read`/`write` credentials. The full
contract - framing, handshake, permissions, event sequencing and gap
reporting, write de-duplication, `request_status` recovery for result-unknown
writes, exclusive leases, limits and the support matrix - lives in
[`docs/control-protocol.md`](docs/control-protocol.md); canonical frames are in
[`protocol/fixtures/control-protocol-1.json`](protocol/fixtures/control-protocol-1.json).

First start with no credentials issues one bootstrap `read+write` credential
and writes it to `control/bootstrap-token.txt` (owner-only; the log only points
at the file); provision per-daemon credentials afterwards with
`/mcagent control token add <label> read|write` and revoke them with
`/mcagent control token revoke <id>`. Credential administration is
console-only: a remote write credential runs ordinary game commands at ADMIN
level but cannot reach the owner-only `/mcagent control` subtree.

Administration and lifecycle:

```
-Dmcagent.control=true                  enable the formal transport
-Dmcagent.controlEventBuffer=16          replay ring size (test default 1024)
-Dmcagent.controlMaxFrameBytes=16777216   frame size cap
-Dmcagent.testClientCommands=true       test-only WORLD_CLOSE and LAN_CLOSE
```

`mcagent.testClientCommands` exists only for the issue #7 lifecycle
experiments (the in-game "Save and Quit" and "Close LAN" paths without a
human at the keyboard) and is not part of the product API. The
`e2e/control_e2e.py` driver runs the dedicated, LAN and WSL scenarios against
the real mod with real Go daemons and records check-by-check evidence.

## Build

Minecraft Java 26.2 ships unobfuscated class files, so the mod is compiled
directly against the client jar and Fabric API:

```bash
python build.py \
  --minecraft-dir "C:/Users/me/AppData/Roaming/.minecraft" \
  --version 26.2-Fabric \
  --jdk "C:/Program Files/Java/jdk-25"
```

Output: `dist/mc-agent-interface-0.7.0.jar`. Put it together with
`fabric-api-*.jar` into the client's `mods/` directory.

## Tests

`test.py` compiles the mod and runs the dependency-free unit tests for the chat
context cache and protocol shapes - capture/correlation, deterministic
eviction, expiry, unknown ids and unavailable senders. It takes the same
`--minecraft-dir`, `--version` and `--jdk` arguments as `build.py`:

```bash
python test.py \
  --minecraft-dir "C:/Users/me/AppData/Roaming/.minecraft" \
  --version 26.2-Fabric \
  --jdk "C:/Program Files/Java/jdk-25"
```

`tests/player_context_test.py` is the protocol test for the server vantage. The
default run never edits terrain and never deletes anything it did not create:
it checks `CAPS`, an unknown player, and a valid player - an existing one with
`--player`, otherwise a uniquely named Carpet fake probe that is removed
afterwards:

```bash
python tests/player_context_test.py --port 25581 --player gua_jun
```

`--allow-world-edits` adds the server-raycast scenarios (block, entity, miss,
and a spear's attack-range case). It clears and places blocks only inside a
small documented box and tags its summoned fixture, but that box is still
destructive: run this mode only against a disposable or isolated world.

```bash
python tests/player_context_test.py --port 25581 --allow-world-edits
```

## Configuration

| System property | Default | Meaning |
| --- | --- | --- |
| `mcagent.dir` | `<gameDir>/mc-agent` | directory for `port.txt`, event and sample files |
| `mcagent.port` | `25580` | first port to try; the mod falls back to the next free port |
| `mcagent.autoConnect` | unset | `host:port`; join that server from the title screen on startup |
| `mcagent.serverDir` | `mc-agent-server` | server vantage: directory for `port.txt`, event and snapshot files |
| `mcagent.serverPort` | `25581` | server vantage: first port to try |
| `mcagent.contextCacheSize` | `256` | server vantage: chat context bundles kept in memory |
| `mcagent.contextCacheTtlSeconds` | `300` | server vantage: how long a bundle stays fetchable after capture |
| `mcagent.samePortSpike` | `false` | issue #7 spike: accept control connections on the game port (isolated tests only) |
| `mcagent.samePortSpikeAllowRemote` | `false` | spike: also accept non-loopback control clients (private LAN only) |
| `mcagent.samePortSpikeHandshakeSeconds` | `10` | spike: close a connection that sends no marker within this many seconds |
| `mcagent.samePortSpikeIdleSeconds` | `300` | spike: close an idle control session after this many seconds |
| `mcagent.samePortSpikeMaxLineBytes` | `65536` | spike: maximum control request line length |

## In-game commands

The same primitives are available as client-side commands, so a human can check
the interface without a bridge or a model:

| Command | Shows |
| --- | --- |
| `/mcagent` or `/mcagent status` | mod version, protocol, port, connected bridges, tick, recording |
| `/mcagent state` | position, velocity, health, entity count, dimension |
| `/mcagent entities [radius]` | nearby entities with id, type, position, velocity |
| `/mcagent port` | the port a bridge should dial |
| `/mcagent caps` | protocol capabilities |
| `/mcagent mark <text>` | writes a marker into the event stream |
| `/mcagent record start <ticks> [radius] [interval]` / `record stop` | per-tick sampling |

These are client commands: they never reach the server, they work in single
player and on any server, and they need nothing but the mod.

## Where this runs, and who the player is

`"environment": "*"`, with both entrypoints in the same jar. Fabric loads only
the one that matches where it is running:

| Entrypoint | Runs in | Serves |
| --- | --- | --- |
| `client` (`InterfaceMod`) | any client | the **client vantage**: what that client can see and do, screens, opening a save, publishing the world to the LAN |
| `main` (`ServerMod`) | any server - a dedicated one, **or the integrated server inside a single-player world** | the **server vantage**: authoritative state, console commands, snapshots |

Neither entrypoint needs the other, and neither needs a server-side plugin in the
world: vanilla, Paper and Fabric servers are all fine. All either of them needs is
a loopback socket to the bridge.

**In single player both run at once, in the same process**: the client vantage on
`mcagent.port` (25580 by default) and the server vantage on `mcagent.serverPort`
(25581), with their data in `<gameDir>/mc-agent/` and `mc-agent-server/`
respectively (`-Dmcagent.dir` / `-Dmcagent.serverDir` to move them). So the
server-side capabilities - authoritative entity state, `/data get`-quality
numbers, the entity tick order, snapshots - are available while you play, with no
server to set up.

It also does **not** create a player. The mod runs inside one client, and
whatever the bridge asks for happens through that client's player - so by
default the agent acts as *your* character.

To have the agent be a player of its own, run a second client instance with the
mod and point a bridge (or a second bridge) at that instance's `port.txt`:

```
instance A (you)        -> mc-agent/port.txt -> bridge A -> your agent loop
instance B (the agent)  -> mc-agent/port.txt -> bridge B -> its agent loop
```

Each bridge owns one client's socket, so the two never fight over the same
player. On an offline-mode server, instance B can simply use a different player
name; on an online-mode server it needs its own account. Launch instance B with
`-Dmcagent.autoConnect=host:port` and it walks into the world by itself.

If you only need a body and not a client, a Carpet fake player is cheaper: the
server vantage can spawn and drive one, and the server can broadcast its replies
as its own voice. See
[who is the agent in game](https://guajun.github.io/mc-agent/player-identity/).

Open question (RFC 0001): whether the bridge should arbitrate between agents
attached to the *same* client, so two loops cannot fight over one player.

## Scope

This repository deliberately does **not** contain:

- MCP or agent-specific code;
- use-case logic or analysis;
- a persistent agent loop;
- server-side game code.

Those live in the sibling repositories (`mc-agent-bridge`, `mc-agent-loop`).

## License

MIT, see [LICENSE](LICENSE).
