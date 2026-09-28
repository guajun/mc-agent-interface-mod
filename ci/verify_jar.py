#!/usr/bin/env python3
"""Verify a built mc-agent-interface jar before it is published.

Checks the embedded fabric.mod.json, the expected entrypoint classes, the
loader/minecraft dependency metadata and, when a tag is supplied, that the jar
version matches it. Prints the SHA-256 for checksums.

    python ci/verify_jar.py --jar dist/mc-agent-interface-0.8.0.jar \
        --version 0.8.0 --tag v0.8.0
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path

REQUIRED_CLASSES = (
    "dev/mcagent/interfacemod/ServerMod.class",
    "dev/mcagent/interfacemod/InterfaceMod.class",
    "dev/mcagent/interfacemod/control/ControlServer.class",
)
REQUIRED_MIXINS = "mc-agent-interface.mixins.json"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--tag", default="")
    args = parser.parse_args()

    jar = Path(args.jar)
    if not jar.is_file():
        raise SystemExit(f"jar not found: {jar}")
    if args.tag and args.tag != f"v{args.version}":
        raise SystemExit(f"tag {args.tag} does not match version {args.version}")

    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        if "fabric.mod.json" not in names:
            raise SystemExit("fabric.mod.json is missing from the jar")
        metadata = json.loads(archive.read("fabric.mod.json"))
        missing = [name for name in REQUIRED_CLASSES if name not in names]
        if missing:
            raise SystemExit(f"missing classes in the jar: {missing}")
        if REQUIRED_MIXINS not in names:
            raise SystemExit(f"missing {REQUIRED_MIXINS}")

    if metadata.get("id") != "mc-agent-interface":
        raise SystemExit(f"unexpected mod id: {metadata.get('id')}")
    if metadata.get("version") != args.version:
        raise SystemExit(
            f"fabric.mod.json version {metadata.get('version')} != expected {args.version}"
        )
    depends = metadata.get("depends") or {}
    for key in ("fabricloader", "minecraft", "fabric-api", "java"):
        if key not in depends:
            raise SystemExit(f"fabric.mod.json is missing a {key} dependency")

    print(json.dumps({
        "jar": jar.name,
        "version": metadata["version"],
        "id": metadata["id"],
        "depends": depends,
        "sha256": sha256_file(jar),
        "bytes": jar.stat().st_size,
    }, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
