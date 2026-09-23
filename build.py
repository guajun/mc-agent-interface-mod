#!/usr/bin/env python3
"""Build the mc-agent-interface Fabric mod without Gradle.

Minecraft Java 26.2 ships unobfuscated class files, so a small client-side
Fabric mod can be compiled directly against the client jar and Fabric API.

Example:
    python mod/build.py \
        --minecraft-dir "C:/Users/me/AppData/Roaming/.minecraft" \
        --version 26.2-Fabric \
        --jdk "C:/Program Files/Java/jdk-25"
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

PROJECT = Path(__file__).resolve().parent
SRC = PROJECT / "src" / "main" / "java"
RESOURCES = PROJECT / "src" / "main" / "resources"

API_MODULES = (
    "fabric-api-base-",
    "fabric-lifecycle-events-v1-",
    "fabric-message-api-v1-",
    "fabric-command-api-v2-",
)


def find_jdk(explicit):
    if explicit:
        return Path(explicit)
    if os.environ.get("JAVA_HOME"):
        return Path(os.environ["JAVA_HOME"])
    javac = shutil.which("javac")
    if javac:
        return Path(javac).resolve().parent.parent
    raise SystemExit("JDK not found: pass --jdk or set JAVA_HOME")


def version_key(path):
    numbers = [int(part) for part in re.findall(r"\d+", path.name)]
    return numbers or [0]


def build_classpath(minecraft_dir, version):
    version_dir = minecraft_dir / "versions" / version
    version_json = version_dir / f"{version}.json"
    game_jar = version_dir / f"{version}.jar"
    if not version_json.exists():
        raise SystemExit(f"missing version json: {version_json}")
    if not game_jar.exists():
        raise SystemExit(f"missing client jar: {game_jar}")
    data = json.loads(version_json.read_text(encoding="utf-8"))
    libraries = minecraft_dir / "libraries"
    entries = [str(game_jar)]
    seen = {str(game_jar)}

    def add(path):
        text = str(path)
        if text not in seen and Path(text).exists():
            seen.add(text)
            entries.append(text)

    for library in data.get("libraries", []):
        artifact = (library.get("downloads") or {}).get("artifact") or {}
        path = artifact.get("path")
        if path:
            add(libraries / path)

    loader_jars = sorted(
        (libraries / "net" / "fabricmc" / "fabric-loader").glob("*/fabric-loader-*.jar")
    )
    if not loader_jars:
        raise SystemExit("fabric-loader not found under <minecraft-dir>/libraries")
    add(max(loader_jars, key=version_key))

    processed = version_dir / ".fabric" / "processedMods"
    modules = []
    for prefix in API_MODULES:
        matches = sorted(processed.glob(prefix + "*.jar")) if processed.exists() else []
        if matches:
            modules.append(max(matches, key=version_key))
    if len(modules) < len(API_MODULES):
        modules = extract_fabric_api_modules(minecraft_dir, modules)
    for module in modules:
        add(module)
    return entries


def extract_fabric_api_modules(minecraft_dir, existing):
    """Extract the API modules we compile against from a Fabric API jar."""
    found = {prefix: None for prefix in API_MODULES}
    for module in existing:
        for prefix in API_MODULES:
            if module.name.startswith(prefix):
                found[prefix] = module
    if all(found.values()):
        return list(found.values())
    candidates = sorted((minecraft_dir / "mods").glob("fabric-api-*.jar"))
    if not candidates:
        raise SystemExit(
            "Fabric API not found. Install fabric-api in <minecraft-dir>/mods "
            "or run the game once so .fabric/processedMods exists."
        )
    target = Path(tempfile.mkdtemp(prefix="mc-agent-interface-api-"))
    with zipfile.ZipFile(max(candidates, key=version_key)) as archive:
        for entry in archive.namelist():
            name = Path(entry).name
            for prefix in API_MODULES:
                if name.startswith(prefix) and found[prefix] is None:
                    archive.extract(entry, target)
                    found[prefix] = target / entry
    missing = [prefix for prefix, value in found.items() if value is None]
    if missing:
        raise SystemExit(f"missing Fabric API modules in {candidates[-1]}: {missing}")
    return list(found.values())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--minecraft-dir", required=True, help="path to the .minecraft directory")
    parser.add_argument("--version", default="26.2-Fabric", help="version folder name")
    parser.add_argument("--jdk", default=None, help="path to a JDK 25 installation")
    parser.add_argument("--output", default=str(PROJECT / "dist"), help="output directory")
    arguments = parser.parse_args()

    minecraft_dir = Path(arguments.minecraft_dir)
    jdk = find_jdk(arguments.jdk)
    javac = jdk / "bin" / "javac.exe" if (jdk / "bin" / "javac.exe").exists() else jdk / "bin" / "javac"
    jar = jdk / "bin" / "jar.exe" if (jdk / "bin" / "jar.exe").exists() else jdk / "bin" / "jar"
    if not javac.exists():
        raise SystemExit(f"javac not found: {javac}")

    classpath = build_classpath(minecraft_dir, arguments.version)
    out_dir = PROJECT / "out"
    shutil.rmtree(out_dir, ignore_errors=True)
    out_dir.mkdir(parents=True)
    sources = sorted(str(path) for path in SRC.rglob("*.java"))
    if not sources:
        raise SystemExit(f"no java sources under {SRC}")
    args_file = PROJECT / "javac.args"
    args_file.write_text(
        "\n".join(["-encoding", "UTF-8", "-nowarn", "-cp", os.pathsep.join(classpath), "-d", str(out_dir), *sources]),
        encoding="utf-8",
    )
    print(f"compiling {len(sources)} sources with {javac}")
    subprocess.run([str(javac), f"@{args_file}"], check=True)
    output = Path(arguments.output)
    output.mkdir(parents=True, exist_ok=True)
    metadata = json.loads(
        (PROJECT / "src" / "main" / "resources" / "fabric.mod.json").read_text(encoding="utf-8")
    )
    target = output / f"mc-agent-interface-{metadata['version']}.jar"
    subprocess.run(
        [str(jar), "--create", "--file", str(target), "-C", str(out_dir), ".", "-C", str(RESOURCES), "."],
        check=True,
    )
    print(f"built: {target}")


if __name__ == "__main__":
    main()

