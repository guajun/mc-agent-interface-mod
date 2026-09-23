# mc-agent-interface

Generic Fabric client mod that exposes a local, versioned interface for agent
runtimes.

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
{"type":"hello","mod":"mc-agent-interface","version":"0.1.0","protocol":1,
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
| `MARK <text>` | write a marker into the event stream |
| `CAPS` | protocol version and capabilities |
| `PING` | liveness check |

`COMMAND`, `SAY`, `STATE_GET`, `ENTITY_LIST`, `RECORD_START` and `RECORD_STOP`
are accepted as aliases so a bridge can use readable names.

Events are pushed to every connected client and appended to
`<gameDir>/mc-agent/events.jsonl`:

```json
{"type":"chat","millis":...,"text":"hello","sender":"LiteralComponent{content='name',...}"}
{"type":"game","millis":...,"text":"..."}
{"type":"sample_start","millis":...,"text":"ticks=600 radius=32 interval=1"}
```

`text` is the message content; `sender` is the stringified display-name
component, so clients should extract the name defensively rather than assume a
bare player name.

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

Output: `dist/mc-agent-interface-0.1.0.jar`. Put it together with
`fabric-api-*.jar` into the client's `mods/` directory.

## Configuration

| System property | Default | Meaning |
| --- | --- | --- |
| `mcagent.dir` | `<gameDir>/mc-agent` | directory for `port.txt`, event and sample files |
| `mcagent.port` | `25580` | first port to try; the mod falls back to the next free port |

## Scope

This repository deliberately does **not** contain:

- MCP or agent-specific code;
- use-case logic or analysis;
- a persistent agent loop;
- server-side game code.

Those live in the sibling repositories (`mc-agent-bridge`, `mc-agent-loop`).

## License

MIT, see [LICENSE](LICENSE).
