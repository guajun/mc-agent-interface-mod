#!/usr/bin/env python3
"""Protocol test for the server-vantage PLAYER operation (issue #1).

Connects to a running mc-agent-interface server-vantage socket - the port in
``<serverDir>/port.txt``, 25581 by default - and checks the per-player context
reply and the server-side view-target raycast:

* CAPS advertises the new capability;
* a valid player resolves by UUID and by name, with a stable UUID, dimension,
  position, rotation and a view target;
* an unknown player is a structured ``found:false`` answer and does not drop
  the connection;
* the raycast returns block, entity and miss targets, all computed on the
  server from the player's eye position and look vector.

The instance under test needs the mod plus Fabric API and Carpet, because the
test spawns a fake probe player when none is given. It works the same against a
dedicated server and the integrated server of a single-player world:

    python tests/player_context_test.py --port 25581
    python tests/player_context_test.py --port 25581 --player gua_jun

The raycast scenarios use a controlled part of the world at y=200: the probe is
teleported there, the ray path is filled with air, and blocks/entities are
placed only for the block and entity cases. Nothing outside that box changes.
"""

from __future__ import annotations

import argparse
import json
import socket
import sys
import time
import uuid

PROBE = "AgentProbe"
PROBE_X = 0.5
PROBE_Y = 200.0
PROBE_Z = 0.5
BLOCK_X = 0
BLOCK_Y = 201
BLOCK_Z = 3
PIG_X = 0.5
PIG_Y = 201.0
PIG_Z = 2.5


class CheckError(AssertionError):
    """A named protocol check failed."""


class ModConnection:
    """One JSON-lines connection to a running interface mod."""

    def __init__(self, host: str, port: int, timeout: float) -> None:
        self.host = host
        self.port = port
        self.timeout = timeout
        self.socket = socket.create_connection((host, port), timeout=timeout)
        self.socket.settimeout(timeout)
        self.reader = self.socket.makefile("r", encoding="utf-8", newline="\n")
        self.hello = self._read_reply(expect_hello=True)
        if self.hello.get("type") != "hello":
            raise CheckError(f"expected a hello line first, got {self.hello!r}")

    def _read_reply(self, expect_hello: bool = False) -> dict:
        """Read until a reply arrives; pushed events are not replies."""
        while True:
            line = self.reader.readline()
            if not line:
                raise CheckError("the mod closed the connection")
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(message, dict) and message.get("event") is True:
                continue
            if not expect_hello and isinstance(message, dict) and message.get("type") == "hello":
                continue
            return message

    def request(self, line: str) -> dict:
        self.socket.sendall((line + "\n").encode("utf-8"))
        reply = self._read_reply()
        if reply.get("type") == "error":
            raise CheckError(f"{line!r} answered with an error: {reply.get('message')}")
        return reply

    def command(self, command: str) -> dict:
        return self.request("CMD " + command)

    def close(self) -> None:
        try:
            self.reader.close()
        except OSError:
            pass
        try:
            self.socket.close()
        except OSError:
            pass


class Checks:
    def __init__(self) -> None:
        self.passed = 0
        self.failures: list[str] = []

    def ok(self, name: str, condition: bool, detail: str = "") -> None:
        if condition:
            self.passed += 1
            print(f"PASS  {name}")
        else:
            text = f"{name} - {detail}" if detail else name
            self.failures.append(text)
            print(f"FAIL  {text}")

    def equal(self, name: str, actual, expected) -> None:
        self.ok(name, actual == expected, f"actual={actual!r} expected={expected!r}")


def wait_for_player(mod: ModConnection, name: str, timeout: float = 20.0) -> dict:
    deadline = time.monotonic() + timeout
    last: dict | None = None
    while time.monotonic() < deadline:
        state = mod.request("STATE")
        for entry in state.get("playerList", []):
            last = entry
            if entry.get("name") == name:
                return entry
        time.sleep(0.25)
    raise CheckError(f"player {name!r} never appeared in STATE (last={last!r})")


def spawn_probe(mod: ModConnection) -> dict:
    """Spawn the fake player if needed and park it at the test spot."""
    mod.command(f"player {PROBE} kill")
    # Spawn directly at the test spot: that also loads the chunk before any
    # fill/setblock runs, so the probe does not fall through an unloaded world
    # while it waits for the Carpet profile check.
    mod.command(f"player {PROBE} spawn at {PROBE_X} {PROBE_Y} {PROBE_Z}")
    player = wait_for_player(mod, PROBE)
    park_probe(mod)
    return player


def park_probe(mod: ModConnection) -> None:
    """Teleport the probe, give it a floor, and clear the ray path at y=200."""
    time.sleep(0.5)
    mod.command(f"fill -2 198 -2 2 204 6 minecraft:air")
    mod.command(f"setblock 0 199 0 minecraft:stone")
    mod.command(
        f"kill @e[x={PROBE_X},y={PROBE_Y},z={PROBE_Z},distance=..8,type=!minecraft:player]"
    )
    mod.command(f"tp {PROBE} {PROBE_X} {PROBE_Y} {PROBE_Z} 0 0")


def view_target(mod: ModConnection, query: str) -> dict:
    reply = mod.request(f"PLAYER {query}")
    if not reply.get("found"):
        raise CheckError(f"expected player {query!r} to be found: {reply!r}")
    return reply.get("view", {}).get("target", {})


def run(args: argparse.Namespace) -> int:
    checks = Checks()
    mod = ModConnection(args.host, args.port, args.timeout)
    online = wait_for_player(mod, args.player) if args.player else None
    try:
        hello = mod.hello
        checks.equal("hello identifies the server vantage", hello.get("instance"), "server")
        capabilities = hello.get("capabilities", [])
        checks.ok("hello advertises player", "player" in capabilities, f"capabilities={capabilities}")

        caps = mod.request("CAPS")
        checks.ok(
            "CAPS advertises player",
            "player" in caps.get("capabilities", []),
            f"capabilities={caps.get('capabilities')}",
        )
        checks.ok(
            "CAPS advertises player:view",
            "player:view" in caps.get("capabilities", []),
            f"capabilities={caps.get('capabilities')}",
        )

        probe = spawn_probe(mod)
        probe_uuid = probe["uuid"]

        # Valid player, by stable UUID.
        by_uuid = mod.request(f"PLAYER {probe_uuid}")
        checks.equal("valid player found by uuid", by_uuid.get("found"), True)
        checks.equal("uuid query matched by uuid", by_uuid.get("matchedBy"), "uuid")
        described = by_uuid.get("player", {})
        checks.equal("reply carries the stable uuid", described.get("uuid"), probe_uuid)
        checks.equal("reply carries the player name", described.get("name"), PROBE)
        checks.ok("reply carries a dimension", bool(described.get("dimension")), str(described))
        checks.ok("reply carries rotation", "yaw" in described and "pitch" in described, str(described))
        view = by_uuid.get("view", {})
        checks.ok("reply carries the view ray", bool(view.get("direction")), str(view))
        checks.ok("reply carries the eye position", bool(view.get("eye")), str(view))

        # Valid player, by name - convenience only.
        by_name = mod.request(f"PLAYER {PROBE}")
        checks.equal("valid player found by name", by_name.get("found"), True)
        checks.equal("name query matched by name", by_name.get("matchedBy"), "name")
        checks.equal(
            "name and uuid resolve to the same player",
            by_name.get("player", {}).get("uuid"),
            probe_uuid,
        )

        # A dashed and a plain UUID both work.
        plain = uuid.UUID(probe_uuid).hex
        by_plain = mod.request(f"PLAYER {plain}")
        checks.equal("plain uuid also resolves", by_plain.get("player", {}).get("uuid"), probe_uuid)

        # Unknown players are structured answers, and the connection survives.
        unknown_uuid = mod.request(f"PLAYER {uuid.uuid4()}")
        checks.equal("unknown uuid is not found", unknown_uuid.get("found"), False)
        checks.ok("unknown uuid carries an error", bool(unknown_uuid.get("error")), str(unknown_uuid))
        unknown_name = mod.request(f"PLAYER no_such_player_{uuid.uuid4().hex[:8]}")
        checks.equal("unknown name is not found", unknown_name.get("found"), False)
        checks.equal("connection survives unknown players", mod.request("PING").get("type"), "pong")

        # The probe is parked in a cleared box, looking along +Z.
        park_probe(mod)
        miss = view_target(mod, probe_uuid)
        checks.equal("raycast miss type", miss.get("type"), "miss")
        checks.ok("miss carries a hit position", bool(miss.get("hit")), str(miss))

        # A block on the look axis is the target.
        mod.command(f"setblock {BLOCK_X} {BLOCK_Y} {BLOCK_Z} minecraft:stone")
        block = view_target(mod, probe_uuid)
        checks.equal("raycast block type", block.get("type"), "block")
        block_info = block.get("block", {})
        checks.equal("block position x", block_info.get("x"), BLOCK_X)
        checks.equal("block position y", block_info.get("y"), BLOCK_Y)
        checks.equal("block position z", block_info.get("z"), BLOCK_Z)
        checks.equal("block id", block_info.get("id"), "minecraft:stone")
        checks.ok("block carries a face", bool(block_info.get("face")), str(block_info))

        # An entity closer than any block is the target.
        mod.command(f"setblock {BLOCK_X} {BLOCK_Y} {BLOCK_Z} minecraft:air")
        mod.command(
            f"summon minecraft:pig {PIG_X} {PIG_Y} {PIG_Z} {{NoGravity:1b,Silent:1b}}"
        )
        entity = view_target(mod, probe_uuid)
        checks.equal("raycast entity type", entity.get("type"), "entity")
        entity_info = entity.get("entity", {})
        checks.equal("entity target type", entity_info.get("type"), "minecraft:pig")
        checks.ok("entity target carries a uuid", bool(entity_info.get("uuid")), str(entity_info))

        # The requested player is the one described, with a live view.
        described_again = mod.request(f"PLAYER {probe_uuid}").get("player", {})
        checks.equal("resolved player stays stable", described_again.get("uuid"), probe_uuid)

        if online is not None:
            live = mod.request(f"PLAYER {online['uuid']}")
            checks.equal("named online player resolves", live.get("player", {}).get("uuid"), online["uuid"])
    finally:
        try:
            mod.command(f"kill @e[type=minecraft:pig,x={PROBE_X},y={PROBE_Y},z={PROBE_Z},distance=..16]")
            mod.command(f"player {PROBE} kill")
        except (OSError, CheckError):
            pass
        mod.close()

    print()
    if checks.failures:
        print(f"{len(checks.failures)} failed, {checks.passed} passed")
        for failure in checks.failures:
            print(f"  - {failure}")
        return 1
    print(f"all {checks.passed} checks passed")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1", help="mod host (loopback)")
    parser.add_argument("--port", type=int, default=25581, help="server-vantage port")
    parser.add_argument("--timeout", type=float, default=30.0, help="socket timeout seconds")
    parser.add_argument(
        "--player",
        default="",
        help="optional real online player name to check in addition to the probe",
    )
    return parser.parse_args()


if __name__ == "__main__":
    try:
        sys.exit(run(parse_args()))
    except (CheckError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(2)
