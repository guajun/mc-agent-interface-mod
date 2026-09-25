#!/usr/bin/env python3
"""Protocol test for the server-vantage PLAYER operation (issue #1).

Connects to a running mc-agent-interface server-vantage socket - the port in
``<serverDir>/port.txt``, 25581 by default - and checks the per-player context
reply and the server-side view-target raycast.

The default run never edits terrain and never deletes anything it did not
create:

* hello and CAPS advertise the new capability;
* a valid player resolves by UUID and by name, with a stable UUID, dimension,
  position, rotation and a view target. The subject is an existing online
  player when ``--player`` is given, otherwise a uniquely named Carpet fake
  probe that is removed again;
* an unknown player is a structured ``found:false`` answer and does not drop
  the connection.

``--allow-world-edits`` adds the raycast scenarios (block, entity, miss and a
spear's attack range). That mode clears and places blocks only inside one small
documented box and summons one tagged pig, so it needs a disposable or isolated
world. It never touches unowned content: the probe carries a per-run name and
the summoned fixture a per-run tag, and cleanup removes exactly those.

    python tests/player_context_test.py --port 25581 --player gua_jun
    python tests/player_context_test.py --port 25581 --allow-world-edits
"""

from __future__ import annotations

import argparse
import json
import socket
import sys
import time
import uuid

#: Unique per run, so the fixture can never collide with a real player and the
#: cleanup can never delete somebody else's entity.
RUN_ID = uuid.uuid4().hex[:8]
PROBE = "Probe" + RUN_ID
FIXTURE_TAG = "mcagent-probe-" + RUN_ID

PROBE_X = 0.5
PROBE_Y = 200.0
PROBE_Z = 0.5
BLOCK_X = 0
BLOCK_Y = 201
BLOCK_Z = 3
PIG_X = 0.5
PIG_Y = 201.0
PIG_Z = 2.5
SPEAR = "minecraft:iron_spear"


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
    """Spawn the uniquely named fake player at the test spot and park it."""
    mod.command(f"player {PROBE} kill")
    mod.command(f"player {PROBE} spawn at {PROBE_X} {PROBE_Y} {PROBE_Z}")
    player = wait_for_player(mod, PROBE)
    park_probe(mod)
    return player


def park_probe(mod: ModConnection) -> None:
    """Point the probe at the cleared box; this does not edit terrain."""
    mod.command(f"tp {PROBE} {PROBE_X} {PROBE_Y} {PROBE_Z} 0 0")


def prepare_ray_box(mod: ModConnection) -> None:
    """Destructive: clear the documented ray box and add a floor under the probe.

    Only called in --allow-world-edits mode. The box is x=-2..2, y=198..204,
    z=-2..6 around the probe; nothing outside it is touched, and no entity is
    removed here.
    """
    time.sleep(0.3)
    mod.command("fill -2 198 -2 2 204 6 minecraft:air")
    mod.command("setblock 0 199 0 minecraft:stone")
    park_probe(mod)


def wait_tick(mod: ModConnection) -> None:
    """Let one server tick pass so summons/removals are visible to the raycast."""
    mod.request("WAIT 1")


def summon_fixture(mod: ModConnection, x: float, y: float, z: float) -> None:
    mod.command(
        f"summon minecraft:pig {x} {y} {z} "
        f'{{NoGravity:1b,Silent:1b,DeathLootTable:"minecraft:empty",Tags:["{FIXTURE_TAG}"]}}'
    )
    wait_tick(mod)


def kill_fixture(mod: ModConnection) -> None:
    """Remove only the entities summoned by this run, then let the tick land."""
    mod.command(f"kill @e[tag={FIXTURE_TAG}]")
    wait_tick(mod)


def equip(mod: ModConnection, item: str) -> None:
    mod.command(f"item replace entity {PROBE} weapon.mainhand with {item}")


def view_target(mod: ModConnection, query: str) -> dict:
    reply = mod.request(f"PLAYER {query}")
    if not reply.get("found"):
        raise CheckError(f"expected player {query!r} to be found: {reply!r}")
    return reply.get("view", {}).get("target", {})


def check_identity(checks: Checks, mod: ModConnection, subject: dict) -> None:
    """The valid-player checks, by UUID, by name and by a plain UUID."""
    by_uuid = mod.request(f"PLAYER {subject['uuid']}")
    checks.equal("valid player found by uuid", by_uuid.get("found"), True)
    checks.equal("uuid query matched by uuid", by_uuid.get("matchedBy"), "uuid")
    described = by_uuid.get("player", {})
    checks.equal("reply carries the stable uuid", described.get("uuid"), subject["uuid"])
    checks.equal("reply carries the player name", described.get("name"), subject["name"])
    checks.ok("reply carries a dimension", bool(described.get("dimension")), str(described))
    checks.ok("reply carries rotation", "yaw" in described and "pitch" in described, str(described))
    view = by_uuid.get("view", {})
    checks.ok("reply carries the view ray", bool(view.get("direction")), str(view))
    checks.ok("reply carries the eye position", bool(view.get("eye")), str(view))

    by_name = mod.request(f"PLAYER {subject['name']}")
    checks.equal("valid player found by name", by_name.get("found"), True)
    checks.equal("name query matched by name", by_name.get("matchedBy"), "name")
    checks.equal(
        "name and uuid resolve to the same player",
        by_name.get("player", {}).get("uuid"),
        subject["uuid"],
    )

    plain = uuid.UUID(subject["uuid"]).hex
    by_plain = mod.request(f"PLAYER {plain}")
    checks.equal("plain uuid also resolves", by_plain.get("player", {}).get("uuid"), subject["uuid"])


def check_unknown(checks: Checks, mod: ModConnection) -> None:
    """Unknown players are structured answers, and the connection survives."""
    unknown_uuid = mod.request(f"PLAYER {uuid.uuid4()}")
    checks.equal("unknown uuid is not found", unknown_uuid.get("found"), False)
    checks.ok("unknown uuid carries an error", bool(unknown_uuid.get("error")), str(unknown_uuid))
    unknown_name = mod.request(f"PLAYER no_such_player_{uuid.uuid4().hex[:8]}")
    checks.equal("unknown name is not found", unknown_name.get("found"), False)
    checks.equal("connection survives unknown players", mod.request("PING").get("type"), "pong")


def check_raycast(checks: Checks, mod: ModConnection) -> None:
    """The destructive scenarios; only run with --allow-world-edits."""
    probe = wait_for_player(mod, PROBE)
    probe_uuid = probe["uuid"]
    prepare_ray_box(mod)

    # The probe is parked in a cleared box, looking along +Z.
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

    # An entity closer than any block is the target (ordinary interaction range).
    mod.command(f"setblock {BLOCK_X} {BLOCK_Y} {BLOCK_Z} minecraft:air")
    summon_fixture(mod, PIG_X, PIG_Y, PIG_Z)
    entity = view_target(mod, probe_uuid)
    checks.equal("raycast entity type", entity.get("type"), "entity")
    entity_info = entity.get("entity", {})
    checks.equal("entity target type", entity_info.get("type"), "minecraft:pig")
    checks.ok("entity target carries a uuid", bool(entity_info.get("uuid")), str(entity_info))

    # The active item's attack range: move the same fixture out past the
    # ordinary entity range, equip the spear, and check the same entity comes
    # back within reach. Moving beats killing here, because a dying entity keeps
    # its hitbox for the death animation and would shadow the real target.
    equip(mod, "minecraft:air")
    ordinary = mod.request(f"PLAYER {probe_uuid}")
    entity_range = float(ordinary["view"]["entityRange"])
    far_z = PROBE_Z + entity_range + 0.8
    mod.command(f"tp @e[tag={FIXTURE_TAG}] {PIG_X} {PIG_Y} {far_z}")
    wait_tick(mod)
    equip(mod, SPEAR)
    spear = view_target(mod, probe_uuid)
    checks.equal("spear attack range selects the far entity", spear.get("type"), "entity")
    checks.ok(
        "spear hit is beyond the ordinary entity range",
        float(spear.get("distance", 0.0)) > entity_range,
        f"distance={spear.get('distance')!r} entityRange={entity_range!r}",
    )
    equip(mod, "minecraft:air")
    fallback = view_target(mod, probe_uuid)
    checks.equal("without the spear the far entity is out of reach", fallback.get("type"), "miss")


def run(args: argparse.Namespace) -> int:
    checks = Checks()
    mod = ModConnection(args.host, args.port, args.timeout)
    created_probe = False
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

        # A real online player when one was named, otherwise an owned probe.
        if args.player:
            subject = wait_for_player(mod, args.player)
        else:
            subject = spawn_probe(mod)
            created_probe = True
        check_identity(checks, mod, subject)
        check_unknown(checks, mod)

        if args.allow_world_edits:
            if not created_probe:
                spawn_probe(mod)
                created_probe = True
            check_raycast(checks, mod)
        else:
            print("SKIP  raycast scenarios: pass --allow-world-edits in a disposable world")
    finally:
        if created_probe:
            try:
                if args.allow_world_edits:
                    kill_fixture(mod)
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
        help="existing online player to check read-only; otherwise an owned probe is spawned",
    )
    parser.add_argument(
        "--allow-world-edits",
        action="store_true",
        help="run the destructive raycast scenarios (disposable/isolated world only)",
    )
    return parser.parse_args()


if __name__ == "__main__":
    try:
        sys.exit(run(parse_args()))
    except (CheckError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(2)
