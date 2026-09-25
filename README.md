# mc-agent-interface

📖 Part of **mc-agent**; the guide lives at <https://guajun.github.io/mc-agent/>.

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
{"type":"hello","mod":"mc-agent-interface","version":"0.6.0","protocol":1,
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

The server vantage does not just broadcast chat text. When the server receives
a chat message it captures the sender's context synchronously - before any
socket client or agent can delay handling - and stores it under an opaque id.
The chat event carries that id plus a compact summary:

```json
{"type":"chat","millis":...,"event":true,"seq":12,"tick":8451,
 "text":"hello","sender":"Notch","context_id":"ctx-2b1d...",
 "context":{"schema":"player-context/1","uuid":"069a79f4-...","name":"Notch",
            "tick":8451,"dimension":"minecraft:overworld",
            "x":10.5,"y":64.0,"z":-3.25,"yaw":180.0,"pitch":12.5,
            "view":{"type":"block"}}}
```

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
            "capturedAt":1790343000000,"tick":8451,"uuid":"069a79f4-...",
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
Bundles contain server-known values only - identity, transform, one view ray,
schema string - never an entity snapshot or a world save, so the chat event
stays small. The server `CAPS` advertises `"context"` and `"events:chat"` for
this.

Entity records for players carry `name` (and `gameMode`), because a client
cannot otherwise tell one player from another - and a Carpet fake player is
just a player entity as far as the client is concerned.

Per-tick samples are appended to `<gameDir>/mc-agent/samples.jsonl`.

## Build

Minecraft Java 26.2 ships unobfuscated class files, so the mod is compiled
directly against the client jar and Fabric API:

```bash
python build.py \
  --minecraft-dir "C:/Users/me/AppData/Roaming/.minecraft" \
  --version 26.2-Fabric \
  --jdk "C:/Program Files/Java/jdk-25"
```

Output: `dist/mc-agent-interface-0.6.0.jar`. Put it together with
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
