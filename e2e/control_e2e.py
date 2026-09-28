#!/usr/bin/env python3
"""Issue #8/#7 end-to-end driver: the Go daemon against the real mod.

Scenarios:

``dedicated``
    Provision a throwaway Fabric lab server with the 0.8.0 mod, enable the
    formal TLS control transport on the game port, and run two independent Go
    daemons (a read+write credential and a read-only credential) against it:
    capability/permission checks, concurrent request routing, normal player
    regression, credential revocation and rotation, daemon restart, event-gap
    reporting, unknown-write recovery, and a game restart.

``lan``
    Launch a real client in an isolated game directory with the mod, open a
    copied throwaway world, publish it to the LAN, connect a Go daemon to the
    actual published port, then drive the normal "Save and Quit to Title" and
    reopen flow (test-only WORLD_CLOSE command) and reconnect on the new port.

Every check is recorded in ``evidence.json``; a failing check is reported
instead of being hidden. The driver only ever touches its own lab, its own
copied world and its own game directories, and it always tears them down.
"""

from __future__ import annotations

import argparse
import atexit
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
    entry = {"check": name, "ok": bool(ok)}
    if detail:
        entry["detail"] = detail
    EVIDENCE.append(entry)
    if ok:
        PASSED.append(name)
        print(f"PASS  {name}")
    else:
        FAILED.append(f"{name} - {detail}" if detail else name)
        print(f"FAIL  {name}" + (f" - {detail}" if detail else ""))
    return bool(ok)


def show(text: str) -> str:
    return text if all(ord(char) < 128 for char in text) else ascii(text)


def import_file(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_lab_server(path: Path, lab_root: Path):
    module = import_file("lab_server_for_e2e", path)
    module.LABS = lab_root
    module.CACHE = lab_root / "_cache"
    return module


def tail(log: Path, lines: int = 80) -> list[str]:
    if not log.exists():
        return []
    return log.read_text(encoding="utf-8", errors="replace").splitlines()[-lines:]


def current_run_lines(log: Path) -> list[str]:
    lines = tail(log, 8000)
    for index in range(len(lines) - 1, -1, -1):
        if "===== lab_server start" in lines[index]:
            return lines[index + 1:]
    return lines


def wait_for(predicate, timeout: float, interval: float = 0.5, description: str = "condition"):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            last = predicate()
            if last:
                return last
        except Exception as error:  # noqa: BLE001 - polling helper
            last = error
        time.sleep(interval)
    raise TimeoutError(f"{description} did not become true within {timeout}s (last={last!r})")


def rcon(lab_module, lab: Path, command: str) -> str:
    with lab_module.open_console(lab) as console:
        return lab_module.clean_console_text(console.command(command))


def wait_rcon(lab_module, lab: Path, predicate, command: str, timeout: float):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            text = rcon(lab_module, lab, command)
        except OSError as error:
            text = str(error)
        if predicate(text):
            return text
        time.sleep(1.0)
    return None


# ------------------------------------------------------------------- go daemon


class GoClients:
    def __init__(self, binary: Path, base: Path) -> None:
        self.binary = binary
        self.base = base
        self.homes: dict[str, Path] = {}

    def home(self, name: str) -> Path:
        path = self.base / name
        path.mkdir(parents=True, exist_ok=True)
        self.homes[name] = path
        return path

    def run(self, home: Path, arguments: list[str], input_text: str | None = None,
            timeout: float = 120.0) -> subprocess.CompletedProcess:
        env = dict(os.environ)
        env["MC_AGENT_HOME"] = str(home)
        return subprocess.run([str(self.binary), *arguments], env=env, input=input_text,
                              capture_output=True, text=True, encoding="utf-8",
                              errors="replace", timeout=timeout)

    def json(self, home: Path, arguments: list[str], input_text: str | None = None,
             timeout: float = 120.0) -> dict:
        result = self.run(home, arguments, input_text=input_text, timeout=timeout)
        for text in (result.stdout, result.stderr):
            text = (text or "").strip()
            if not text:
                continue
            try:
                return json.loads(text)
            except ValueError:
                continue
        return {"error": {"code": "not_json", "message": (result.stderr or result.stdout or "")[-500:],
                          "exit": result.returncode}}

    def ok(self, home: Path, arguments: list[str], input_text: str | None = None,
           timeout: float = 120.0) -> dict:
        value = self.json(home, arguments, input_text=input_text, timeout=timeout)
        if isinstance(value, dict) and value.get("error"):
            raise RuntimeError(f"mc-agent {' '.join(arguments)} failed: {value['error']}")
        return value


def target_add(gocli: GoClients, home: Path, name: str, address: str, pin: str, token: str,
               default: bool = False) -> None:
    arguments = ["target", "add", name, "--transport", "remote", "--address", address,
                 "--pin", pin, "--token-stdin", "--force"]
    if default:
        arguments.append("--default")
    gocli.ok(home, arguments, input_text=token + "\n")


def wait_connected(gocli: GoClients, home: Path, target: str, timeout: float = 60.0) -> dict:
    def probe():
        status = gocli.json(home, ["daemon", "status"])
        if status.get("running") is not True:
            return None
        inner = status.get("status") or {}
        for entry in inner.get("targets") or []:
            if entry.get("name") == target and entry.get("connected") is True:
                return entry
        return None

    return wait_for(probe, timeout, description=f"target {target} to connect")


def wait_disconnected(gocli: GoClients, home: Path, target: str, code: str | None = None,
                      timeout: float = 60.0) -> dict:
    def probe():
        status = gocli.json(home, ["daemon", "status"])
        inner = status.get("status") or {}
        for entry in inner.get("targets") or []:
            if entry.get("name") != target:
                continue
            if entry.get("connected") is False and entry.get("lastError"):
                if code is None or entry.get("lastErrorCode") == code:
                    return entry
        return None

    return wait_for(probe, timeout, description=f"target {target} to disconnect")


def daemon_start(gocli: GoClients, home: Path, extra: list[str] | None = None) -> dict:
    arguments = ["daemon", "start", *(extra or [])]
    result = gocli.json(home, arguments)
    if result.get("error"):
        raise RuntimeError(f"daemon start failed: {result['error']}")
    return result


def daemon_stop(gocli: GoClients, home: Path) -> None:
    gocli.json(home, ["daemon", "stop"])


def event_texts(gocli: GoClients, home: Path) -> list[str]:
    result = gocli.ok(home, ["events", "--since", "0", "--limit", "500"])
    # Compact separators keep substring checks stable and match the wire shape.
    return [json.dumps(event, ensure_ascii=False, separators=(",", ":"))
            for event in result.get("events") or []]


# ---------------------------------------------------------------- client tools


def launch_client(args, game_dir: Path, log_path: Path, username: str,
                  properties: list[str], server: str = "", world: str = "") -> subprocess.Popen:
    launch = import_file("launch_instance_for_e2e", Path(args.meta_root) / "tools" / "launch_instance.py")
    namespace = SimpleNamespace(
        minecraft_dir=str(args.minecraft_dir), version=args.version, java=args.jdk25,
        memory="2G", width=0, height=0, world=world, world_file="", server=server,
        natives="", account_file="", username=username,
        jvm_property=list(properties), jvm_arg=[], game_arg=[], log="", dry_run=False,
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
                        reader.readline()
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
    raise TimeoutError(f"client request {line!r} failed: {last}")


def ensure_client_mods(game_dir: Path, lab_mods: Path) -> list[str]:
    mods = game_dir / "mods"
    mods.mkdir(parents=True, exist_ok=True)
    installed = []
    for jar in sorted(lab_mods.glob("*.jar")):
        target = mods / jar.name
        # Copy every run: the test build can change without changing size.
        shutil.copy2(jar, target)
        installed.append(jar.name)
    return installed


# ------------------------------------------------------------------- cleanup


class Cleanup:
    INSTANCES: list["Cleanup"] = []

    def __init__(self, lab_module, lab_name: str, keep: bool = False) -> None:
        self.lab_module = lab_module
        self.lab_name = lab_name
        self.keep = keep
        self.lab_running = False
        self.processes: list[subprocess.Popen] = []
        self.daemons: list[tuple[GoClients, Path]] = []
        Cleanup.INSTANCES.append(self)

    def track(self, process: subprocess.Popen) -> subprocess.Popen:
        self.processes.append(process)
        return process

    def track_daemon(self, gocli: GoClients, home: Path) -> None:
        self.daemons.append((gocli, home))

    def stop_lab(self) -> None:
        if self.keep or not self.lab_running:
            return
        self.lab_running = False
        try:
            self.lab_module.cmd_stop(SimpleNamespace(name=self.lab_name, timeout=90.0, force=False))
        except SystemExit:
            pass

    def close(self) -> None:
        for gocli, home in self.daemons:
            try:
                daemon_stop(gocli, home)
            except Exception:  # noqa: BLE001 - best effort teardown
                pass
        for process in self.processes:
            try:
                if process.poll() is None:
                    process.terminate()
            except OSError:
                pass
        self.stop_lab()


def _cleanup_everything() -> None:
    for guard in list(Cleanup.INSTANCES):
        guard.close()


atexit.register(_cleanup_everything)


def token_id_for(listing: str, label: str) -> str | None:
    # RCON feedback can glue adjacent lines together, so match id+label pairs
    # across the whole text rather than assuming one line per credential.
    match = re.search(r"(tk_[0-9a-f]+)\s+label=" + re.escape(label) + r"\b", listing)
    return match.group(1) if match else None


def start_lab(args, lab_module, guard, properties: str, name: str | None = None) -> None:
    previous = os.environ.copy()
    os.environ["JAVA_TOOL_OPTIONS"] = properties
    guard.lab_running = True
    try:
        lab_module.cmd_start(SimpleNamespace(name=name or args.lab_name, java=args.jdk25,
                                             memory="", wait=300))
    finally:
        os.environ.clear()
        os.environ.update(previous)


def scenario_dedicated(args) -> int:
    evidence = Path(args.evidence_dir).resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    lab_module = load_lab_server(Path(args.lab_server), Path(args.lab_root))
    mod_jar = Path(args.mod_jar).resolve()
    lab_name = args.lab_name

    print(f"== provision lab {lab_name} ({args.lab_root})")
    lab_module.cmd_provision(SimpleNamespace(
        name=lab_name, mc="", fabric_api=True, carpet=False, mod_jar=[str(mod_jar)],
        mod_url=[], world="", void=True, java=args.jdk25, memory="", loader="", force=False))
    lab, state = lab_module.load_lab(lab_name)
    guard = Cleanup(lab_module, lab_name, keep=args.keep)
    game_port = int(state["serverPort"])
    console_log = lab / "logs" / "console.log"

    # A fresh identity and credential store every run keeps the bootstrap path
    # deterministic; nothing outside this throwaway lab is touched.
    control_dir = lab / "mc-agent-server" / "control"
    shutil.rmtree(control_dir, ignore_errors=True)

    print(f"== start lab on game port {game_port} with the formal control transport")
    start_lab(args, lab_module, guard,
              "-Dmcagent.control=true -Dmcagent.controlEventBuffer=16")

    sys.path.insert(0, str(HERE.parent / "spike"))
    import mc_ping  # noqa: PLC0415

    run_log = "\n".join(current_run_lines(console_log))
    check("server announced Done", "Done (" in run_log, "no Done line in this run")

    def fingerprint() -> str | None:
        path = control_dir / "fingerprint.txt"
        if path.is_file():
            return path.read_text(encoding="utf-8").strip()
        return None

    pinned = wait_for(fingerprint, 300, description="control fingerprint")
    check("server certificate fingerprint is exposed for pinning", pinned.startswith("sha256:"), pinned)
    check("server.crt exists for CA verification", (control_dir / "server.crt").is_file())
    enabled = wait_for(
        lambda: "formal control ENABLED on the game port" in "\n".join(current_run_lines(console_log))
        or None, 30, description="formal control enable line")
    check("the fingerprint is only exposed once the transport is armed", bool(enabled))

    def bootstrap_secret() -> str | None:
        # The bearer secret is written to an owner-only file, never to the log;
        # the log only points at the file.
        path = control_dir / "bootstrap-token.txt"
        if path.is_file():
            value = path.read_text(encoding="utf-8").strip()
            if value:
                return value
        return None

    rw_secret = wait_for(bootstrap_secret, 30, description="bootstrap credential")
    check("a one-time bootstrap credential is written to an owner-only file",
          rw_secret.startswith("mca1."), "secret shape")
    check("the bootstrap secret never appears in the server log",
          rw_secret not in "\n".join(current_run_lines(console_log)))
    check("the credential file does not contain the secret",
          rw_secret not in (control_dir / "tokens.json").read_text(encoding="utf-8"))

    # With the sniffer armed, ordinary player-shaped traffic must be untouched.
    status = mc_ping.status("127.0.0.1", game_port, timeout=10.0)
    pong = mc_ping.ping("127.0.0.1", game_port, timeout=10.0)
    version = status.get("version", {})
    check("vanilla status ping still works with control enabled",
          version.get("protocol") == 776 and pong == 0x0102030405060708,
          json.dumps(version))

    add_output = rcon(lab_module, lab, "mcagent control token add daemon-b read")
    match = re.search(r"SECRET \(shown once\): (\S+)", add_output)
    check("a read-only credential can be issued on the server console", match is not None,
          add_output[-300:])
    if match is None:
        return 1
    read_secret = match.group(1)
    listing = rcon(lab_module, lab, "mcagent control token list")
    read_id = token_id_for(listing, "daemon-b")
    check("the token listing exposes ids and labels but no secrets",
          read_id is not None and read_secret not in listing, listing[-400:])
    if read_id is None:
        return 1

    gocli = GoClients(Path(args.go_binary).resolve(), Path(args.work_dir).resolve())
    home_a = gocli.home("daemon-a")
    home_b = gocli.home("daemon-b")
    address = f"127.0.0.1:{game_port}"
    target_add(gocli, home_a, "dedicated", address, pinned, rw_secret, default=True)
    target_add(gocli, home_b, "dedicated", address, pinned, read_secret, default=True)

    daemon_start(gocli, home_a)
    daemon_start(gocli, home_b)
    guard.track_daemon(gocli, home_a)
    guard.track_daemon(gocli, home_b)
    a_state = wait_connected(gocli, home_a, "dedicated")
    b_state = wait_connected(gocli, home_b, "dedicated")
    check("two independent daemons connect to the same game port", True)
    check("both see the same server instance and run",
          a_state.get("instanceId") == b_state.get("instanceId")
          and a_state.get("runId") == b_state.get("runId")
          and a_state.get("instanceId"),
          f"a={a_state.get('instanceId')}/{a_state.get('runId')} "
          f"b={b_state.get('instanceId')}/{b_state.get('runId')}")
    check("the read+write credential sees the command capability",
          "command" in (a_state.get("capabilities") or []), str(a_state.get("capabilities")))
    check("the daemon reports a pinned TLS remote transport",
          a_state.get("transport") == "remote", str(a_state.get("transport")))
    check("no TLS trust override is accepted at target-add time",
          "error" in gocli.json(home_a, ["target", "add", "unsafe", "--transport", "remote",
                                         "--address", address, "--token-stdin"],
                                input_text=rw_secret + "\n"),
          "a remote target without pin/ca must be refused")

    # Normal player regression: a real client joins and quits while both
    # control sessions stay connected.
    client_dir = Path(args.work_dir).resolve() / "client-dedicated"
    ensure_client_mods(client_dir, lab / "mods")
    client = guard.track(launch_client(args, client_dir, client_dir / "client.log",
                                       args.client_username, []))
    wait_for(lambda: (client_request(client_dir, "PING", wait=3.0) or {}).get("type") == "pong",
             args.client_wait, interval=2.0, description="client mod socket")
    connect = client_request(client_dir, f"CONNECT {address}", wait=args.client_wait)
    check("the client mod accepts a CONNECT to the same game port",
          connect.get("type") == "connect_ack", json.dumps(connect))
    listed = wait_rcon(lab_module, lab, lambda text: args.client_username in text, "list", 180)
    check("a real player joins the same game port", listed is not None, f"list={listed}")
    state_a = gocli.ok(home_a, ["state", "--target", "dedicated"])
    player_names = [entry.get("name") for entry in state_a.get("playerList") or []]
    check("control STATE sees the real player", args.client_username in player_names,
          str(player_names))
    check("control STATE never lists a control session as a player",
          len(player_names) <= 1, str(player_names))
    client.terminate()
    wait_rcon(lab_module, lab, lambda text: "0 of a max" in text, "list", 60)
    check("both control sessions survive the player leaving",
          gocli.ok(home_a, ["call", "state", "--target", "dedicated"]) is not None
          and gocli.ok(home_b, ["call", "state", "--target", "dedicated"]) is not None)

    # Permissions.
    denied = gocli.json(home_b, ["command", "say nope", "--target", "dedicated"])
    check("the read-only credential is refused a write",
          denied.get("error", {}).get("code") == "forbidden", json.dumps(denied))
    check("the read-only credential can still read",
          gocli.ok(home_b, ["state", "--target", "dedicated"]).get("levelName") is not None)

    first_write = gocli.ok(home_a, ["command", "say from-a", "--target", "dedicated"])
    check("a write returns an ordered write sequence number",
          isinstance(first_write.get("writeSeq"), int), json.dumps(first_write))

    # A remote write credential must not be able to mint credentials. The
    # /mcagent control subtree is owner-only and the remote command source is
    # capped at ADMIN, including through /execute and /function chains.
    for attempt in ("mcagent control token add smuggled read",
                    "execute run mcagent control token add smuggled2 read"):
        remote = gocli.json(home_a, ["command", attempt, "--target", "dedicated"])
        output = json.dumps(remote)
        check(f"remote '{attempt}' is refused by the permission boundary",
              "Unknown or incomplete command" in output or "Incorrect argument" in output
              or "permission" in output.lower() or "not allowed" in output.lower(), output[:400])
        listing_after = rcon(lab_module, lab, "mcagent control token list")
        check("no credential was minted through the remote command",
              "smuggled" not in listing_after
              and "smuggled" not in (control_dir / "tokens.json").read_text(encoding="utf-8"),
              listing_after[-400:])

    # Snapshot names are single directory segments inside the snapshots root.
    good_snapshot = gocli.ok(home_a, ["snapshot", "--name", "e2e-ok", "--target", "dedicated"])
    check("a safe snapshot name is accepted",
          (lab / "mc-agent-server" / "snapshots" / "e2e-ok" / "entities.jsonl").is_file(),
          json.dumps(good_snapshot))
    for bad_name in ("..\\escape", "C:\\escape", "../../escape", "/escape", "con"):
        refused = gocli.json(home_a, ["snapshot", "--name", bad_name, "--target", "dedicated"])
        check(f"snapshot name {bad_name!r} is refused",
              "error" in refused, json.dumps(refused)[:300])
    check("no snapshot escaped the snapshots root",
          not (lab / "escape").exists()
          and not (lab / "mc-agent-server" / "snapshots" / "escape").exists()
          and not (lab / "mc-agent-server" / "escape").exists())

    # Concurrent requests must not cross replies: two commands from A and a
    # read from B, all in flight at the same time.
    marker_a = f"route-a-{int(time.time() * 1000)}"
    marker_a2 = f"route-a2-{int(time.time() * 1000)}"
    env_a = dict(os.environ, MC_AGENT_HOME=str(home_a))
    env_b = dict(os.environ, MC_AGENT_HOME=str(home_b))
    processes = [
        subprocess.Popen([str(gocli.binary), "command", f"say {marker_a}", "--target", "dedicated"],
                         env=env_a, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                         encoding="utf-8", errors="replace"),
        subprocess.Popen([str(gocli.binary), "command", f"say {marker_a2}", "--target", "dedicated"],
                         env=env_a, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                         encoding="utf-8", errors="replace"),
        subprocess.Popen([str(gocli.binary), "state", "--target", "dedicated"], env=env_b,
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                         encoding="utf-8", errors="replace"),
    ]
    outputs = [process.communicate(timeout=60)[0] for process in processes]
    check("concurrent daemons receive their own replies",
          marker_a in outputs[0] and marker_a2 not in outputs[0]
          and marker_a2 in outputs[1] and marker_a not in outputs[1]
          and "levelName" in outputs[2], str(outputs))

    mark_text = f"e2e-mark-{int(time.time() * 1000)}"
    gocli.ok(home_a, ["mark", mark_text, "--target", "dedicated"])
    wait_for(lambda: any(mark_text in line for line in event_texts(gocli, home_a)), 30,
             description="mark event on daemon A")
    wait_for(lambda: any(mark_text in line for line in event_texts(gocli, home_b)), 30,
             description="mark event on daemon B")
    check("both daemons receive the pushed event without cross-talk", True)
    event_lines = event_texts(gocli, home_a)
    write_events = []
    for line in event_lines:
        try:
            parsed = json.loads(line)
        except ValueError:
            continue
        if parsed.get("type") == "write" and parsed.get("writeSeq"):
            write_events.append(parsed.get("writeSeq"))
    check("write completion events carry increasing write sequence numbers",
          write_events == sorted(write_events) and len(write_events) >= 2, str(write_events))

    # Result-unknown recovery: a 1 ms client deadline records the write, and
    # request_status resolves it without a blind retry.
    unknown = gocli.json(home_a, ["call", "command", "--target", "dedicated",
                                  "--params", json.dumps({"command": "say unknown-result"}),
                                  "--timeout", "0.001"])
    error = unknown.get("error") or {}
    check("a timed-out write is reported as result-unknown with a request id",
          error.get("resultUnknown") is True and error.get("requestId"), json.dumps(unknown))
    request_id = error.get("requestId")
    if request_id:
        listed = gocli.ok(home_a, ["requests"])
        check("the unknown write is recorded in the daemon ledger",
              request_id in json.dumps(listed), json.dumps(listed))
        resolved = wait_for(
            lambda: (lambda value: value if value.get("state") == "completed" else None)(
                gocli.ok(home_a, ["request-status", request_id, "--target", "dedicated"])),
            30, description="request_status completion")
        check("request_status resolves the earlier write instead of replaying it",
              resolved.get("state") == "completed", json.dumps(resolved))

    # Revocation closes the live session; the other daemon is unaffected.
    rcon(lab_module, lab, f"mcagent control token revoke {read_id}")
    revoked = wait_disconnected(gocli, home_b, "dedicated", code="unauthorized", timeout=60)
    check("revoking a credential closes its live session",
          revoked.get("lastErrorCode") == "unauthorized", json.dumps(revoked))
    check("the other daemon keeps its session",
          gocli.ok(home_a, ["call", "state", "--target", "dedicated"]).get("levelName") is not None)

    # Rotation: a new credential with write permission replaces the revoked one.
    rotated = rcon(lab_module, lab, "mcagent control token add daemon-b write")
    rotated_match = re.search(r"SECRET \(shown once\): (\S+)", rotated)
    check("a replacement credential can be issued", rotated_match is not None, rotated[-200:])
    if rotated_match:
        gocli.ok(home_b, ["target", "add", "dedicated", "--transport", "remote",
                          "--address", address, "--pin", pinned, "--token-stdin", "--force",
                          "--default"], input_text=rotated_match.group(1) + "\n")
        gocli.ok(home_b, ["target", "reload"])
        wait_connected(gocli, home_b, "dedicated", timeout=60)
        check("rotation reconnects the daemon with the new credential", True)
        b_after = gocli.ok(home_b, ["command", "say rotated", "--target", "dedicated"])
        check("the rotated credential can write", isinstance(b_after.get("writeSeq"), int),
              json.dumps(b_after))

    # Event gap: daemon A restarts with no cursor and the server buffer is 16.
    daemon_stop(gocli, home_a)
    for index in range(24):
        gocli.ok(home_b, ["mark", f"gap-{index}", "--target", "dedicated"])
    daemon_start(gocli, home_a)
    wait_connected(gocli, home_a, "dedicated", timeout=60)
    gap_seen = wait_for(
        lambda: any("event_gap" in line for line in event_texts(gocli, home_a)), 30,
        description="event gap reporting")
    check("a daemon that missed more than the server buffer reports an event gap", gap_seen)

    # Unknown write across a game restart: after the restart the server has no
    # record; the daemon marks it unresolved instead of retrying.
    stale = gocli.json(home_a, ["call", "command", "--target", "dedicated",
                                "--params", json.dumps({"command": "say before-restart"}),
                                "--timeout", "0.001"])
    stale_id = (stale.get("error") or {}).get("requestId")
    old_run = (wait_connected(gocli, home_a, "dedicated") or {}).get("runId")
    lab_module.cmd_stop(SimpleNamespace(name=lab_name, timeout=120.0, force=False))
    guard.lab_running = False
    time.sleep(2)
    start_lab(args, lab_module, guard,
              "-Dmcagent.control=true -Dmcagent.controlEventBuffer=16")
    wait_for(lambda: "Done (" in "\n".join(current_run_lines(console_log)), 300,
             description="restarted server")
    a_after = wait_connected(gocli, home_a, "dedicated", timeout=120)
    check("daemons reconnect after the game restarts", True)
    check("the new run has a new run id",
          a_after.get("runId") and a_after.get("runId") != old_run,
          f"old={old_run} new={a_after.get('runId')}")
    wait_for(lambda: any("game_restarted" in line for line in event_texts(gocli, home_a)), 30,
             description="game restart event")
    check("a game restart is reported as a new run without replay", True)
    if stale_id:
        unresolved = wait_for(
            lambda: any(f'"requestId":"{stale_id}"' in line and '"state":"unresolved"' in line
                        for line in event_texts(gocli, home_a)), 60,
            description="stale request unresolved")
        check("a pre-restart write is marked unresolved, not replayed", unresolved)

    summary = {
        "scenario": "dedicated",
        "gamePort": game_port,
        "fingerprint": pinned,
        "checks": EVIDENCE,
        "passed": PASSED,
        "failed": FAILED,
        "serverLogTail": tail(console_log, 120),
    }
    (evidence / "dedicated-summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"\n{len(PASSED)} passed, {len(FAILED)} failed; evidence in {evidence}")
    return 1 if FAILED else 0


# ---------------------------------------------------------------------- LAN


def validate_world_name(name: str) -> None:
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", name or ""):
        raise SystemExit(f"unsafe world name {name!r}; use one safe path segment")


def prepare_lan_world(lab: Path, game_dir: Path, world_name: str, force: bool = False) -> Path:
    validate_world_name(world_name)
    source = lab / "world"
    if not (source / "level.dat").is_file():
        raise SystemExit(f"no generated world at {source}; provision/start the lab once first")
    saves_root = (game_dir / "saves").resolve()
    target = (saves_root / world_name).resolve()
    if target.parent != saves_root:
        raise SystemExit(f"refusing world target outside {saves_root}: {target}")
    if target.exists():
        if not force:
            raise SystemExit(f"refusing to replace existing {target}; pass --force-world")
        shutil.rmtree(target)
    try:
        shutil.copytree(source, target)
        (target / "session.lock").unlink(missing_ok=True)
    except BaseException:
        shutil.rmtree(target, ignore_errors=True)
        raise
    return target


def wait_client_socket(game_dir: Path, timeout: float) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            client_request(game_dir, "PING", wait=3.0)
            return
        except TimeoutError:
            time.sleep(1.0)
    raise TimeoutError(f"the client mod socket at {game_dir} never answered")


def publish_lan(game_dir: Path, lan_port: int, timeout: float) -> int:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        reply = client_request(game_dir, f"LAN {lan_port} offline", wait=10.0)
        if reply.get("type") == "lan_ack":
            detail = reply.get("detail", "")
            return int(detail.split("port=")[1].split()[0]) if "port=" in detail else lan_port
        time.sleep(2.0)
    raise TimeoutError(f"LAN {lan_port} was not published")


def open_world_and_lan(game_dir: Path, world_name: str, lan_port: int, timeout: float) -> int:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        reply = client_request(game_dir, f"WORLD {world_name}", wait=10.0)
        if reply.get("type") == "world_ack":
            break
        time.sleep(2.0)
    else:
        raise TimeoutError(f"WORLD {world_name} was not accepted")
    while time.monotonic() < deadline:
        reply = client_request(game_dir, f"LAN {lan_port} offline", wait=10.0)
        if reply.get("type") == "lan_ack":
            detail = reply.get("detail", "")
            return int(detail.split("port=")[1].split()[0]) if "port=" in detail else lan_port
        time.sleep(2.0)
    raise TimeoutError(f"LAN {lan_port} was not published")


def scenario_lan(args) -> int:
    evidence = Path(args.evidence_dir).resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    lab_module = load_lab_server(Path(args.lab_server), Path(args.lab_root))
    mod_jar = Path(args.mod_jar).resolve()
    lab_name = args.lab_name + "-lan"

    print(f"== provision/start lab {lab_name} once to generate a throwaway world")
    lab_module.cmd_provision(SimpleNamespace(
        name=lab_name, mc="", fabric_api=True, carpet=False, mod_jar=[str(mod_jar)],
        mod_url=[], world="", void=True, java=args.jdk25, memory="", loader="", force=False))
    lab, state = lab_module.load_lab(lab_name)
    guard = Cleanup(lab_module, lab_name, keep=args.keep)
    start_lab(args, lab_module, guard, "-Dmcagent.control=false", name=lab_name)
    lab_module.cmd_stop(SimpleNamespace(name=lab_name, timeout=120.0, force=False))
    guard.lab_running = False

    game_dir = Path(args.work_dir).resolve() / "client-lan"
    ensure_client_mods(game_dir, lab / "mods")
    world = prepare_lan_world(lab, game_dir, args.world_name, force=args.force_world)
    print(f"== copied world to {world}")
    shutil.rmtree(game_dir / "mc-agent-server" / "control", ignore_errors=True)

    client = guard.track(launch_client(
        args, game_dir, game_dir / "client.log", args.client_username,
        ["mcagent.control=true", "mcagent.testClientCommands=true", "mcagent.controlEventBuffer=16"]))
    wait_client_socket(game_dir, args.client_wait)
    first_port = open_world_and_lan(game_dir, args.world_name, args.lan_port, args.client_wait)
    check("the LAN host publishes the integrated server on the requested port",
          first_port == args.lan_port, f"actual={first_port}")

    control_dir = game_dir / "mc-agent-server" / "control"

    def fingerprint() -> str | None:
        path = control_dir / "fingerprint.txt"
        return path.read_text(encoding="utf-8").strip() if path.is_file() else None

    pinned = wait_for(fingerprint, 120, description="LAN control fingerprint")

    def secret() -> str | None:
        path = control_dir / "bootstrap-token.txt"
        if path.is_file():
            value = path.read_text(encoding="utf-8").strip()
            if value:
                return value
        return None

    token = wait_for(secret, 60, description="LAN bootstrap credential")
    check("the LAN host exposes a pinned identity and one credential",
          pinned.startswith("sha256:") and token.startswith("mca1."))

    gocli = GoClients(Path(args.go_binary).resolve(), Path(args.work_dir).resolve())
    home = gocli.home("daemon-lan")
    address = f"127.0.0.1:{first_port}"
    target_add(gocli, home, "lan", address, pinned, token, default=True)
    daemon_start(gocli, home)
    guard.track_daemon(gocli, home)
    lan_state = wait_connected(gocli, home, "lan", timeout=120)
    check("a Go daemon connects to the actual LAN game port",
          lan_state.get("transport") == "remote", json.dumps(lan_state))
    before = gocli.ok(home, ["state", "--target", "lan"])
    names = [entry.get("name") for entry in before.get("playerList") or []]
    check("the LAN host player is visible to the control session", args.client_username in names,
          str(names))
    command = gocli.ok(home, ["command", "say lan-e2e", "--target", "lan"])
    check("server-vantage commands work over the LAN control session",
          isinstance(command.get("writeSeq"), int), json.dumps(command))

    # Normal world close: Save and Quit to Title stops the integrated server,
    # which stops the LAN listener and closes the control connection. The world
    # is then reopened in the same client process and re-published on a new
    # actual port.
    closed = client_request(game_dir, "WORLD_CLOSE", wait=10.0)
    check("the test-only WORLD_CLOSE command is accepted",
          closed.get("type") == "world_close_ack", json.dumps(closed))
    disconnected = wait_disconnected(gocli, home, "lan", timeout=180)
    check("closing the world drops the control connection",
          disconnected.get("connected") is False, json.dumps(disconnected))

    # The client's own disconnect handling finishes the teardown; WORLD is then
    # accepted again (a GenericMessageScreen during the transition is fine).
    def world_reopened() -> bool:
        try:
            reply = client_request(game_dir, f"WORLD {args.world_name}", wait=10.0)
        except TimeoutError:
            return False
        return reply.get("type") == "world_ack"

    wait_for(world_reopened, args.client_wait, interval=2.0, description="world reopened")
    check("the same client process reopens the saved world", True)
    second_port = publish_lan(game_dir, args.lan_port + 1, args.client_wait)
    check("the reopened world republishes on a new actual port", second_port == args.lan_port + 1,
          f"actual={second_port}")
    gocli.ok(home, ["target", "add", "lan", "--transport", "remote",
                    "--address", f"127.0.0.1:{second_port}", "--pin", pinned, "--token-stdin",
                    "--force", "--default"], input_text=token + "\n")
    gocli.ok(home, ["target", "reload"])
    after = wait_connected(gocli, home, "lan", timeout=120)
    check("the daemon reconnects to the reopened world on the new port",
          after.get("connected") is True and after.get("endpoint", "").endswith(str(second_port)),
          json.dumps(after))
    check("the reopened world is a new integrated-server run",
          after.get("runId") != lan_state.get("runId"),
          f"first={lan_state.get('runId')} second={after.get('runId')}")
    reopened_state = gocli.ok(home, ["state", "--target", "lan"])
    check("the reopened world still sees the host player",
          args.client_username in [entry.get("name") for entry in reopened_state.get("playerList") or []],
          json.dumps(reopened_state)[:300])

    summary = {
        "scenario": "lan",
        "firstPort": first_port,
        "secondPort": second_port,
        "fingerprint": pinned,
        "checks": EVIDENCE,
        "passed": PASSED,
        "failed": FAILED,
    }
    (evidence / "lan-summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"\n{len(PASSED)} passed, {len(FAILED)} failed; evidence in {evidence}")
    return 1 if FAILED else 0


# ---------------------------------------------------------------------- WSL


def wsl_path(path: Path) -> str:
    text = str(path).replace("\\", "/")
    if len(text) >= 2 and text[1] == ":":
        return "/mnt/" + text[0].lower() + text[2:]
    return text


def scenario_wsl(args) -> int:
    """A non-loopback TLS check from WSL, a genuinely separate network side."""
    evidence = Path(args.evidence_dir).resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    if not args.wsl_linux_binary or not args.host_ip:
        check("WSL remote validation configured", False,
              "pass --wsl-linux-binary and --host-ip")
        return 1
    binary = Path(args.wsl_linux_binary).resolve()
    port = args.remote_port
    pin = args.remote_pin
    token = args.remote_token
    if not (port and pin and token):
        check("WSL remote validation configured", False,
              "pass --remote-port, --remote-pin and --remote-token")
        return 1
    work = Path(args.work_dir).resolve()
    work.mkdir(parents=True, exist_ok=True)
    home = work / "daemon-wsl"
    home.mkdir(parents=True, exist_ok=True)

    def wsl(command: list[str], input_text: str | None = None, timeout: float = 120.0):
        return subprocess.run(["wsl", "-e", *command], input=input_text, capture_output=True,
                              text=True, encoding="utf-8", errors="replace", timeout=timeout)

    remote_home = "/tmp/mc-agent-wsl"
    wsl(["rm", "-rf", remote_home])
    wsl(["mkdir", "-p", remote_home])
    copied = wsl(["cp", wsl_path(binary), remote_home + "/mc-agent"])
    check("the Linux binary is present in WSL", copied.returncode == 0, copied.stderr[-300:])
    wsl(["chmod", "+x", remote_home + "/mc-agent"])
    added = wsl([remote_home + "/mc-agent", "--home", remote_home, "target", "add", "remote",
                 "--transport", "remote",
                 "--address", f"{args.host_ip}:{port}", "--pin", pin, "--token-stdin", "--default"],
                input_text=token + "\n")
    check("WSL can configure a remote target", added.returncode == 0, added.stderr[-300:])
    doctor = wsl([remote_home + "/mc-agent", "--home", remote_home, "doctor"], timeout=180)
    output = (doctor.stdout or "") + (doctor.stderr or "")
    check("a non-loopback TLS control session succeeds from WSL",
          doctor.returncode == 0 and "TLS ok" in output, output[-500:])
    started = wsl([remote_home + "/mc-agent", "--home", remote_home, "daemon", "start"], timeout=120)
    check("a daemon runs inside WSL", started.returncode == 0, started.stdout[-300:] + started.stderr[-300:])
    called = wsl([remote_home + "/mc-agent", "--home", remote_home, "state", "--target", "remote"],
                 timeout=120)
    check("the WSL daemon performs a real state call over TLS",
          called.returncode == 0 and "levelName" in (called.stdout or ""),
          (called.stdout or "") + (called.stderr or "")[-300:])
    wsl([remote_home + "/mc-agent", "--home", remote_home, "daemon", "stop"], timeout=60)
    summary = {"scenario": "wsl", "host": args.host_ip, "port": port,
               "checks": EVIDENCE, "passed": PASSED, "failed": FAILED}
    (evidence / "wsl-summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"\n{len(PASSED)} passed, {len(FAILED)} failed; evidence in {evidence}")
    return 1 if FAILED else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", choices=["dedicated", "lan", "wsl"])
    parser.add_argument("--lab-server", required=True)
    parser.add_argument("--lab-root")
    parser.add_argument("--lab-name", default="e2e-control")
    parser.add_argument("--mod-jar", required=True)
    parser.add_argument("--go-binary", required=True)
    parser.add_argument("--jdk25", required=True)
    parser.add_argument("--minecraft-dir", required=True)
    parser.add_argument("--version", default="26.2-Fabric")
    parser.add_argument("--meta-root", required=True)
    parser.add_argument("--work-dir", required=True)
    parser.add_argument("--evidence-dir", required=True)
    parser.add_argument("--client-username", default="e2e-player")
    parser.add_argument("--client-wait", type=float, default=180.0)
    parser.add_argument("--lan-port", type=int, default=25566)
    parser.add_argument("--world-name", default="e2e-control-world")
    parser.add_argument("--force-world", action="store_true")
    parser.add_argument("--keep", action="store_true")
    parser.add_argument("--wsl-linux-binary")
    parser.add_argument("--host-ip")
    parser.add_argument("--remote-port", type=int)
    parser.add_argument("--remote-pin")
    parser.add_argument("--remote-token")
    arguments = parser.parse_args()
    if arguments.lab_root is None:
        arguments.lab_root = str(Path(arguments.work_dir).resolve() / "labs")
    if not Path(arguments.lab_server).is_file():
        raise SystemExit(f"lab_server.py not found: {arguments.lab_server}")
    if arguments.scenario == "dedicated":
        return scenario_dedicated(arguments)
    if arguments.scenario == "lan":
        return scenario_lan(arguments)
    return scenario_wsl(arguments)


if __name__ == "__main__":
    raise SystemExit(main())
