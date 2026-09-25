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
{"type":"player","instance":"server","protocol":1,"modVersion":"0.6.0",
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
