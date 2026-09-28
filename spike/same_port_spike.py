#!/usr/bin/env python3
"""Issue #7 experiment driver: same-port control connections on a real server.

Two scenarios, both opt-in and isolated:

``dedicated``
    Provision a throwaway Fabric lab server (``tools/lab_server.py`` from the
    mc-agent meta checkout) with the built mod, start it with
    ``-Dmcagent.samePortSpike=true``, run two independent Go probes on the game
    port, and - when a Minecraft client is available - connect a real client
    player, disconnect it, and confirm the control connections keep reading
    state. Nothing touches a real save: the lab has its own world, its own
    ports, and the client gets its own game directory.

``lan``
    Launch a real client in an isolated game directory with the mod, open a
    copied throwaway world, publish it to the LAN on an explicit port, run
    probes against that actual port, close the world, then reopen it on a
    different port and connect again.

Every check is reported honestly: a scenario that cannot run (no Java, no
client, no network) fails with the reason instead of claiming success.

Example (Windows, from the meta checkout that holds the sibling repos)::

    python spike/same_port_spike.py dedicated \
        --lab-server F:/mc-agent/tools/lab_server.py \
        --lab-root F:/mc-agent/.worktrees/artifacts/issue-39/labs \
        --mod-jar dist/mc-agent-interface-0.7.0.jar \
        --probe F:/mc-agent/.worktrees/artifacts/issue-39/spike-build/control-probe.exe \
        --jdk25 "C:/Users/me/AppData/Roaming/.hmcl/java/.../bin/java.exe" \
        --meta-root F:/mc-agent --minecraft-dir "D:/MC/MC_Game/.minecraft" \
        --game-dir F:/mc-agent/.worktrees/artifacts/issue-39/client-dedicated \
        --evidence-dir F:/mc-agent/.worktrees/artifacts/issue-39/run-dedicated
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path
from types import SimpleNamespace

HERE = Path(__file__).resolve().parent

PASSED: list[str] = []
FAILED: list[str] = []
EVIDENCE: list[dict] = []


def check(name: str, ok: bool, detail: str = "") -> bool:
    if ok:
        PASSED.append(name)
        print(f"PASS  {name}")
    else:
        FAILED.append(f"{name} - {detail}" if detail else name)
        print(f"FAIL  {name} - {detail}" if detail else f"FAIL  {name}")
    EVIDENCE.append({"check": name, "ok": bool(ok), "detail": detail})
    return bool(ok)


def import_file(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise SystemExit(f"cannot import {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def load_lab_server(path: Path, lab_root: Path):
    module = import_file("lab_server_for_spike", path)
    module.LABS = lab_root
    module.CACHE = lab_root / "_cache"
    return module


def tail(log: Path, lines: int = 60) -> list[str]:
    if not log.exists():
        return []
    return log.read_text(encoding="utf-8", errors="replace").splitlines()[-lines:]


def current_run_lines(log: Path) -> list[str]:
    """Only the lines after the last lab_server start marker.

    The console log is appended to across runs, so without this every check
    would match the previous run's join/Done lines.
    """
    lines = tail(log, 6000)
    for index in range(len(lines) - 1, -1, -1):
        if "===== lab_server start" in lines[index]:
            return lines[index + 1:]
    return lines


def count_log(log: Path, needle: str) -> int:
    return sum(1 for line in current_run_lines(log) if needle in line)


def wait_log(log: Path, needle: str, timeout: float) -> bool:
    return wait_log_count(log, needle, 1, timeout)


def wait_log_count(log: Path, needle: str, minimum: int, timeout: float) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if count_log(log, needle) >= minimum:
            return True
        time.sleep(0.5)
    return False


def rcon(lab_module, lab: Path, command: str) -> str:
    with lab_module.open_console(lab) as console:
        return lab_module.clean_console_text(console.command(command))


def build_probe(probe_src: Path, output: Path, go: str) -> Path:
    output.parent.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ)
    env.setdefault("GOCACHE", str(output.parent / "go-cache"))
    env.setdefault("GOPATH", str(output.parent / "go-path"))
    subprocess.run([go, "build", "-o", str(output), "."], cwd=str(probe_src), env=env, check=True)
    return output


def run_probe(probe: Path, addr: str, name: str, hold: float, log: Path,
              disconnect_ok: bool = False) -> subprocess.Popen:
    command = [str(probe), "-addr", addr, "-name", name, "-hold", f"{hold}s", "-poll", "1s"]
    if disconnect_ok:
        command.append("-disconnect-ok")
    handle = open(log, "wb")
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0
    return subprocess.Popen(command, stdout=handle, stderr=subprocess.STDOUT, creationflags=flags)


def probe_result(log: Path) -> dict:
    for line in reversed(log.read_text(encoding="utf-8", errors="replace").splitlines()):
        if line.startswith("RESULT "):
            return json.loads(line[len("RESULT "):])
    raise SystemExit(f"no RESULT line in {log}")


def probe_events(log: Path, kind: str) -> list[dict]:
    events = []
    for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
        if line.startswith("EVENT "):
            event = json.loads(line[len("EVENT "):])
            if event.get("event") == kind:
                events.append(event)
    return events


def launch_client(args, game_dir: Path, log_path: Path, username: str,
                  server: str = "", world: str = "") -> subprocess.Popen:
    launch = import_file("launch_instance_for_spike", args.meta_root / "tools" / "launch_instance.py")
    namespace = SimpleNamespace(
        minecraft_dir=str(args.minecraft_dir), version=args.version, java=args.jdk25,
        memory="2G", width=0, height=0, world=world, world_file="", server=server,
        natives="", account_file="", username=username,
        # The client's integrated server needs the same explicit opt-in as the
        # dedicated lab; -D on a client JVM is not inherited from the shell env.
        jvm_property=["mcagent.samePortSpike=true"], jvm_arg=[],
        game_arg=[], log="", dry_run=False,
    )
    command, _version_dir, _natives = launch.build_command(namespace)
    index = command.index("--gameDir")
    command[index + 1] = str(game_dir)
    game_dir.mkdir(parents=True, exist_ok=True)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    handle = open(log_path, "ab")
    flags = getattr(subprocess, "DETACHED_PROCESS", 0) | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
    return subprocess.Popen(command, cwd=str(game_dir), stdout=handle, stderr=subprocess.STDOUT,
                            stdin=subprocess.DEVNULL, creationflags=flags)


def client_request(game_dir: Path, line: str, wait: float = 60.0, timeout: float = 15.0) -> dict:
    """Send one request to the client mod's loopback socket, waiting for it."""
    port_file = game_dir / "mc-agent" / "port.txt"
    deadline = time.monotonic() + wait
    last = "the client mod socket never appeared"
    while time.monotonic() < deadline:
        if port_file.exists():
            try:
                port = int(port_file.read_text(encoding="utf-8").strip())
            except (ValueError, OSError):
                port = 0
            if port:
                try:
                    with socket.create_connection(("127.0.0.1", port), timeout=timeout) as sock:
                        reader = sock.makefile("r", encoding="utf-8", newline="\n")
                        reader.readline()  # hello
                        sock.sendall((line + "\n").encode("utf-8"))
                        while True:
                            reply = reader.readline()
                            if not reply:
                                last = "client socket closed"
                                break
                            message = json.loads(reply)
                            if message.get("event") is True or message.get("type") == "hello":
                                continue
                            return message
                except (OSError, ValueError, json.JSONDecodeError) as error:
                    last = str(error)
        time.sleep(0.5)
    raise SystemExit(f"client request {line!r} failed: {last}")


def ensure_client_mods(game_dir: Path, lab_mods: Path) -> list[str]:
    mods = game_dir / "mods"
    mods.mkdir(parents=True, exist_ok=True)
    installed = []
    for jar in sorted(lab_mods.glob("*.jar")):
        target = mods / jar.name
        if not target.exists() or target.stat().st_size != jar.stat().st_size:
            shutil.copy2(jar, target)
        installed.append(jar.name)
    return installed


# --------------------------------------------------------------------- dedicated


def scenario_dedicated(args) -> int:
    evidence = args.evidence_dir
    evidence.mkdir(parents=True, exist_ok=True)
    lab_module = load_lab_server(args.lab_server, args.lab_root)
    probe = args.probe or build_probe(HERE / "probe", evidence / "control-probe.exe", args.go)

    mod_jar = args.mod_jar.resolve()
    print(f"== provision lab {args.lab_name} ({args.lab_root})")
    lab_module.cmd_provision(SimpleNamespace(
        name=args.lab_name, mc="", fabric_api=True, carpet=False, mod_jar=[str(mod_jar)],
        mod_url=[], world="", void=True, java=args.jdk25, memory="", loader="", force=False))
    lab, state = lab_module.load_lab(args.lab_name)
    game_port = int(state["serverPort"])
    rcon_port = int(state["rconPort"])
    console_log = lab / "logs" / "console.log"

    print(f"== start lab on game port {game_port} with the spike enabled")
    env = dict(os.environ, JAVA_TOOL_OPTIONS="-Dmcagent.samePortSpike=true")
    previous_env = os.environ.copy()
    os.environ.update(env)
    try:
        lab_module.cmd_start(SimpleNamespace(name=args.lab_name, java=args.jdk25, memory="", wait=300))
    finally:
        os.environ.clear()
        os.environ.update(previous_env)

    sys.path.insert(0, str(HERE))
    import mc_ping  # noqa: PLC0415 - found via sys.path above

    status = {}
    try:
        status = mc_ping.status("127.0.0.1", game_port, timeout=10.0)
        pong = mc_ping.ping("127.0.0.1", game_port, timeout=10.0)
    except OSError as error:
        check("vanilla status ping on the game port", False, str(error))
    else:
        version = status.get("version", {})
        check("vanilla status ping on the game port",
              version.get("protocol") == 776 and version.get("name") == "26.2",
              json.dumps(version))
        check("vanilla status ping round-trip", pong == 0x0102030405060708, f"pong={pong!r}")
    run_log = "\n".join(current_run_lines(console_log))
    check("server announced Done", "Done (" in run_log, "no Done line in this run")
    check("spike enabled in the server log",
          "same-port control spike ENABLED" in run_log, "opt-in line missing from this run")

    players_before = rcon(lab_module, lab, "list")
    check("RCON list before control shows no players", "0 of a max" in players_before, players_before)

    print("== two independent Go control probes")
    hold = args.probe_hold
    probe_a = run_probe(probe, f"127.0.0.1:{game_port}", "A", hold, evidence / "probe-A.log")
    time.sleep(args.stagger)
    probe_b = run_probe(probe, f"127.0.0.1:{game_port}", "B", hold - args.stagger, evidence / "probe-B.log")

    client = None
    player_joined_at = None
    if args.client:
        client_dir = args.game_dir.resolve()
        mods = ensure_client_mods(client_dir, lab / "mods")
        print(f"== launch real client ({args.client_username}) with mods {mods}")
        client_log = client_dir / "client.log"
        client = launch_client(args, client_dir, client_log, args.client_username)
        reply = client_request(client_dir, f"CONNECT 127.0.0.1:{game_port}", wait=args.client_wait)
        check("client mod accepted CONNECT", reply.get("type") == "connect_ack", json.dumps(reply))
        joined = wait_log(console_log, f"{args.client_username} joined the game", args.client_wait)
        check("real client player joined the game", joined,
              "\n".join(tail(console_log, 30)) if not joined else "")
        if joined:
            player_joined_at = time.monotonic()
            rcon(lab_module, lab, f"gamemode spectator {args.client_username}")
            during = rcon(lab_module, lab, "list")
            check("RCON list shows the real player", args.client_username in during, during)
            time.sleep(args.player_hold)
            print("== disconnect the real client")
            client.terminate()
            left = wait_log(console_log, f"{args.client_username} lost connection", 30) or \
                wait_log(console_log, f"{args.client_username} left the game", 30)
            check("real client player disconnected", left,
                  "\n".join(tail(console_log, 20)) if not left else "")
            after = rcon(lab_module, lab, "list")
            check("RCON list after the real player left is empty", "0 of a max" in after, after)

            print("== reconnect the real client to prove the player path survives")
            client2 = launch_client(args, client_dir, client_log, args.client_username)
            reply2 = client_request(client_dir, f"CONNECT 127.0.0.1:{game_port}", wait=args.client_wait)
            check("client mod accepted the reconnect", reply2.get("type") == "connect_ack", json.dumps(reply2))
            rejoined = wait_log_count(console_log, f"{args.client_username} joined the game", 2,
                                      args.client_wait)
            check("real client player reconnected", rejoined,
                  "\n".join(tail(console_log, 20)) if not rejoined else "")
            time.sleep(args.player_hold)
            client2.terminate()
            releave = wait_log_count(console_log, f"{args.client_username} lost connection", 2, 30) or \
                wait_log_count(console_log, f"{args.client_username} left the game", 2, 30)
            check("reconnecting player disconnected again", releave,
                  "\n".join(tail(console_log, 20)) if not releave else "")
    else:
        print("== no client requested; player regression not exercised")

    probe_a.wait(timeout=hold + 60)
    probe_b.wait(timeout=hold + 60)
    result_a = probe_result(evidence / "probe-A.log")
    result_b = probe_result(evidence / "probe-B.log")

    check("probe A succeeded end to end", bool(result_a.get("ok")), result_a.get("error", ""))
    check("probe B succeeded end to end", bool(result_b.get("ok")), result_b.get("error", ""))
    check("probe A spoke the spike transport", "same-port-spike" in result_a.get("hello", ""))
    check("probe B spoke the spike transport", "same-port-spike" in result_b.get("hello", ""))
    check("probe A saw two concurrent control sessions",
          result_a.get("control_sessions_max", 0) >= 2, str(result_a.get("control_sessions_max")))
    check("probe B saw two concurrent control sessions",
          result_b.get("control_sessions_max", 0) >= 2, str(result_b.get("control_sessions_max")))
    check("probe A closed and the server closed its side too",
          result_a.get("server_closed") is True, str(result_a.get("server_closed")))
    check("probe B closed and the server closed its side too",
          result_b.get("server_closed") is True, str(result_b.get("server_closed")))

    if args.client and player_joined_at is not None:
        check("probes saw the real player online",
              result_a.get("players_max", 0) >= 1 and result_b.get("players_max", 0) >= 1,
              f"A={result_a.get('players_max')} B={result_b.get('players_max')}")
        polls_a = result_a.get("polls", [])
        online_at = next((index for index, poll in enumerate(polls_a) if poll.get("players", 0) >= 1), None)
        offline_after = online_at is not None and any(
            poll.get("players", 0) == 0 and poll.get("control_sessions", 0) >= 2
            for poll in polls_a[online_at + 1:])
        check("probes saw the real player leave while control stayed connected", offline_after,
              f"players={result_a.get('players_min')}-{result_a.get('players_max')} "
              f"sessions={result_a.get('control_sessions_max')} polls={len(polls_a)}")

    server_log = "\n".join(current_run_lines(console_log))
    joined_control = re.search(r"spike-\d+.*joined the game", server_log)
    check("no control session ever joined the game", joined_control is None,
          joined_control.group(0) if joined_control else "")
    initial_state = result_a.get("initial_state", "")
    check("spike transport is observable in STATE",
          '"samePortSpike"' in initial_state and '"transport":"same-port-spike"' in initial_state,
          initial_state[:200])

    after_all = rcon(lab_module, lab, "list")
    check("RCON list after all probes shows no players", "0 of a max" in after_all, after_all)

    summary = {
        "scenario": "dedicated",
        "lab": args.lab_name,
        "game_port": game_port,
        "rcon_port": rcon_port,
        "mod_jar": str(mod_jar),
        "probe": str(probe),
        "probe_a": result_a,
        "probe_b": result_b,
        "player": args.client_username if args.client else None,
        "checks": EVIDENCE,
        "passed": PASSED,
        "failed": FAILED,
        "server_log_tail": tail(console_log, 80),
    }
    (evidence / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")

    if not args.keep:
        print("== stop lab")
        lab_module.cmd_stop(SimpleNamespace(name=args.lab_name, timeout=120.0, force=False))

    print(f"\n{len(PASSED)} passed, {len(FAILED)} failed; evidence in {evidence}")
    return 1 if FAILED else 0


# --------------------------------------------------------------------------- lan


def prepare_lan_world(lab: Path, game_dir: Path, world_name: str) -> Path:
    source = lab / "world"
    if not (source / "level.dat").exists():
        raise SystemExit(f"no generated world at {source}; provision/start the lab once first")
    target = game_dir / "saves" / world_name
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(source, target)
    (target / "session.lock").unlink(missing_ok=True)
    return target


def wait_for_client(game_dir: Path, timeout: float) -> None:
    """Wait until the client mod answers on its loopback socket."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            client_request(game_dir, "PING", wait=3.0)
            return
        except SystemExit:
            time.sleep(1.0)
    raise SystemExit(f"the client mod socket at {game_dir} never answered")


def open_world_and_lan(args, game_dir: Path, client_log: Path, world_name: str,
                       lan_port: int, username: str):
    client = launch_client(args, game_dir, client_log, username)
    wait_for_client(game_dir, args.client_wait)
    # The title screen must be idle before WORLD is accepted.
    deadline = time.monotonic() + args.client_wait
    while True:
        reply = client_request(game_dir, f"WORLD {world_name}", wait=10.0)
        if reply.get("type") == "world_ack":
            break
        if time.monotonic() > deadline:
            raise SystemExit(f"WORLD {world_name} refused: {reply}")
        time.sleep(2.0)
    # Then publish to LAN; the world may still be loading, so poll.
    deadline = time.monotonic() + args.client_wait
    while True:
        reply = client_request(game_dir, f"LAN {lan_port} offline", wait=10.0)
        if reply.get("type") == "lan_ack":
            return client, reply
        if time.monotonic() > deadline:
            raise SystemExit(f"LAN {lan_port} refused: {reply}")
        time.sleep(2.0)


def scenario_lan(args) -> int:
    evidence = args.evidence_dir
    evidence.mkdir(parents=True, exist_ok=True)
    lab_module = load_lab_server(args.lab_server, args.lab_root)
    probe = args.probe or build_probe(HERE / "probe", evidence / "control-probe.exe", args.go)
    mod_jar = args.mod_jar.resolve()

    # The lab exists only to generate a throwaway world to host.
    print(f"== provision/start lab {args.lab_name} once to generate a world")
    lab_module.cmd_provision(SimpleNamespace(
        name=args.lab_name, mc="", fabric_api=True, carpet=False, mod_jar=[str(mod_jar)],
        mod_url=[], world="", void=True, java=args.jdk25, memory="", loader="", force=False))
    lab, state = lab_module.load_lab(args.lab_name)
    previous_env = os.environ.copy()
    os.environ["JAVA_TOOL_OPTIONS"] = "-Dmcagent.samePortSpike=true"
    try:
        lab_module.cmd_start(SimpleNamespace(name=args.lab_name, java=args.jdk25, memory="", wait=300))
    finally:
        os.environ.clear()
        os.environ.update(previous_env)
    lab_module.cmd_stop(SimpleNamespace(name=args.lab_name, timeout=120.0, force=False))

    game_dir = args.game_dir.resolve()
    ensure_client_mods(game_dir, lab / "mods")
    world = prepare_lan_world(lab, game_dir, args.world_name)
    print(f"== copied world to {world} (isolated game dir)")

    console_log = game_dir / "client.log"
    client_log = game_dir / "logs" / "latest.log"

    def attempt(port: int, tag: str, hold: float, close_world: bool) -> dict:
        client, reply = open_world_and_lan(args, game_dir, console_log, args.world_name, port, args.client_username)
        detail = reply.get("detail", "")
        actual_port = int(detail.split("port=")[1].split()[0]) if "port=" in detail else port
        check(f"LAN {tag} published on the requested port {port}", actual_port == port, str(reply))
        log = evidence / f"probe-lan-{tag}.log"
        running = run_probe(probe, f"127.0.0.1:{actual_port}", f"LAN-{tag}-A", hold, log, disconnect_ok=True)
        saw_state = wait_probe_event(log, "state", timeout=40.0)
        check(f"LAN {tag} probe completed hello/ping/STATE", saw_state, "no state event within 40s")
        time.sleep(3.0)
        if close_world:
            print("== close the world (terminate the host client)")
            client.terminate()
            running.wait(timeout=hold + 60)
            result = probe_result(log)
            check("the world closing disconnected the control connection",
                  result.get("disconnected") is True,
                  json.dumps(result.get("disconnect_error")))
            closed = False
            for _ in range(20):
                if not port_listening(actual_port):
                    closed = True
                    break
                time.sleep(1.0)
            check("first LAN port is no longer listening", closed, f"port {actual_port} still answers")
        else:
            running.wait(timeout=hold + 60)
            result = probe_result(log)
            check("a control connection can close cleanly while the LAN world stays open",
                  result.get("server_closed") is True, str(result.get("server_closed")))
            client.terminate()
        check(f"LAN {tag} probe reached the actual LAN port {actual_port}",
              result.get("hello_port") == actual_port, f"hello_port={result.get('hello_port')}")
        state = json.loads(result.get("initial_state", "{}"))
        names = [entry.get("name") for entry in state.get("playerList", [])]
        check(f"LAN {tag} host player is in control STATE", args.client_username in names, str(names))
        return {"client": client, "reply": reply, "result": result, "port": actual_port}

    first = attempt(args.lan_port, "first", args.probe_hold, close_world=True)
    second = attempt(args.lan_port + 1, "second", args.lan_close_hold, close_world=False)

    summary = {
        "scenario": "lan",
        "first_port": first["port"],
        "second_port": second["port"],
        "first": first["result"],
        "second": second["result"],
        "checks": EVIDENCE,
        "passed": PASSED,
        "failed": FAILED,
    }
    (evidence / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"\n{len(PASSED)} passed, {len(FAILED)} failed; evidence in {evidence}")
    return 1 if FAILED else 0


def wait_probe_event(log: Path, kind: str, timeout: float) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if log.exists() and any(event.get("event") == kind for event in probe_events(log, kind)):
            return True
        time.sleep(0.5)
    return False


def port_listening(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=2.0):
            return True
    except OSError:
        return False


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="scenario", required=True)

    for name in ("dedicated", "lan"):
        scenario = sub.add_parser(name, help=f"{name} scenario")
        scenario.add_argument("--lab-server", type=Path, required=True,
                              help="path to mc-agent tools/lab_server.py")
        scenario.add_argument("--lab-root", type=Path, required=True, help="directory for throwaway labs")
        scenario.add_argument("--lab-name", default=f"issue7-{name}")
        scenario.add_argument("--mod-jar", type=Path, required=True)
        scenario.add_argument("--probe", type=Path, default=None, help="prebuilt Go probe (else go build)")
        scenario.add_argument("--go", default="go")
        scenario.add_argument("--jdk25", required=True, help="Java 25 executable (MC 26.2 needs class file 69)")
        scenario.add_argument("--evidence-dir", type=Path, required=True)
        scenario.add_argument("--probe-hold", type=float, default=90.0, help="seconds each probe stays connected")
        scenario.add_argument("--keep", action="store_true", help="leave the lab server running")

    dedicated = sub.choices["dedicated"]
    dedicated.add_argument("--stagger", type=float, default=3.0)
    dedicated.add_argument("--client", action="store_true", help="also connect a real client player")
    dedicated.add_argument("--client-username", default="issue7-player")
    dedicated.add_argument("--player-hold", type=float, default=8.0)
    dedicated.add_argument("--client-wait", type=float, default=120.0)
    dedicated.add_argument("--meta-root", type=Path, default=None,
                           help="mc-agent meta checkout that holds tools/launch_instance.py (with --client)")
    dedicated.add_argument("--minecraft-dir", type=Path, default=None,
                           help="launcher game root with versions/ and libraries/ (with --client)")
    dedicated.add_argument("--version", default="26.2-Fabric")
    dedicated.add_argument("--game-dir", type=Path, required=False, default=None)

    lan = sub.choices["lan"]
    lan.add_argument("--client-username", default="issue7-host")
    lan.add_argument("--client-wait", type=float, default=180.0)
    lan.add_argument("--meta-root", type=Path, required=True,
                     help="mc-agent meta checkout that holds tools/launch_instance.py")
    lan.add_argument("--minecraft-dir", type=Path, required=True,
                     help="launcher game root with versions/ and libraries/")
    lan.add_argument("--version", default="26.2-Fabric")
    lan.add_argument("--game-dir", type=Path, required=True)
    lan.add_argument("--world-name", default="issue7-lan")
    lan.add_argument("--lan-port", type=int, default=25565)
    lan.add_argument("--lan-close-hold", type=float, default=20.0,
                     help="probe hold for the reopen attempt, where the probe closes itself")

    args = parser.parse_args()
    if args.probe is not None and not args.probe.is_file():
        raise SystemExit(f"--probe is not a file: {args.probe}")
    if not args.mod_jar.is_file():
        raise SystemExit(f"--mod-jar is not a file: {args.mod_jar}")
    if args.scenario == "dedicated":
        if args.client:
            if args.game_dir is None:
                raise SystemExit("--game-dir is required with --client")
            if args.meta_root is None or args.minecraft_dir is None:
                raise SystemExit("--meta-root and --minecraft-dir are required with --client")
        return scenario_dedicated(args)
    return scenario_lan(args)


if __name__ == "__main__":
    raise SystemExit(main())
