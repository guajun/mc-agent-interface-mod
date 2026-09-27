#!/usr/bin/env python3
"""Compile and run the interface-mod unit tests without Gradle or JUnit.

The main sources are compiled with the same classpath ``build.py`` uses, then
the test-only entry point runs on the JVM. The tests themselves only touch the
pure protocol and cache classes, so no Minecraft process is launched.

Example:
    python test.py \
        --minecraft-dir "C:/Users/me/AppData/Roaming/.minecraft" \
        --version 26.2-Fabric \
        --jdk "C:/Program Files/Java/jdk-25"
"""

import argparse
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

from build import PROJECT, SRC, build_classpath, find_jdk

TEST_SRC = PROJECT / "src" / "test" / "java"
TEST_MAIN = "dev.mcagent.interfacemod.InterfaceModTests"


def executable(jdk: Path, name: str) -> Path:
    suffix = ".exe" if os.name == "nt" else ""
    candidate = jdk / "bin" / f"{name}{suffix}"
    if not candidate.exists():
        raise SystemExit(f"not found: {candidate}")
    return candidate


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--minecraft-dir", required=True, help="path to the .minecraft directory")
    parser.add_argument("--version", default="26.2-Fabric", help="version folder name")
    parser.add_argument("--jdk", default=None, help="path to a JDK 25 installation")
    arguments = parser.parse_args()

    jdk = find_jdk(arguments.jdk)
    javac = executable(jdk, "javac")
    java = executable(jdk, "java")
    classpath = build_classpath(Path(arguments.minecraft_dir), arguments.version)

    test_main = TEST_SRC / "dev" / "mcagent" / "interfacemod" / "InterfaceModTests.java"
    if not test_main.exists():
        raise SystemExit(f"no test sources under {TEST_SRC}")

    out_dir = PROJECT / "out" / "test"
    shutil.rmtree(out_dir, ignore_errors=True)
    out_dir.mkdir(parents=True)
    sources = sorted(str(path) for path in SRC.rglob("*.java"))
    sources += sorted(str(path) for path in TEST_SRC.rglob("*.java"))

    with tempfile.NamedTemporaryFile("w", suffix=".args", delete=False, encoding="utf-8") as handle:
        handle.write("\n".join(
            ["-encoding", "UTF-8", "-nowarn", "-cp", os.pathsep.join(classpath),
             "-d", str(out_dir), *sources]))
        args_file = handle.name
    try:
        print(f"compiling {len(sources)} sources with {javac}")
        subprocess.run([str(javac), "@" + args_file], check=True)
    finally:
        os.unlink(args_file)

    print(f"running {TEST_MAIN}")
    subprocess.run(
        [str(java), "-cp", os.pathsep.join([str(out_dir), *classpath]), TEST_MAIN],
        check=True,
    )


if __name__ == "__main__":
    main()
