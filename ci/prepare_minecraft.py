#!/usr/bin/env python3
"""Prepare a build-only Minecraft directory for build.py in CI.

The repository does not ship a Minecraft installation. This script builds the
minimum layout ``build.py`` needs from public sources:

* the vanilla client jar and version JSON for the requested release, via the
  official Mojang version manifest (SHA-1 verified);
* the Fabric loader profile JSON from the Fabric meta service;
* every library the profile names, from the Fabric Maven mirror;
* the Fabric API aggregator jar for the matching Minecraft version.

It is a release-build tool; users install the released mod jar instead.

    python ci/prepare_minecraft.py --minecraft-dir .ci-minecraft \
        --version 26.2 --loader 0.19.5 --fabric-api 0.161.0
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

USER_AGENT = "mc-agent-interface-release/1.0 (+https://github.com/guajun/mc-agent-interface-mod)"
VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
FABRIC_META = "https://meta.fabricmc.net/v2/versions/loader"
FABRIC_MAVEN = "https://maven.fabricmc.net"


def fetch(url: str, timeout: int = 120) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def download(url: str, destination: Path, sha1: str = "", attempts: int = 3) -> None:
    if destination.exists() and destination.stat().st_size > 0:
        if not sha1 or sha1_file(destination) == sha1:
            print(f"cache hit  {destination}")
            return
        destination.unlink()
    destination.parent.mkdir(parents=True, exist_ok=True)
    last_error: Exception | None = None
    for attempt in range(1, attempts + 1):
        try:
            print(f"download   {url}")
            payload = fetch(url)
            if sha1 and hashlib.sha1(payload).hexdigest() != sha1:
                raise SystemExit(f"SHA-1 mismatch for {url}")
            destination.write_bytes(payload)
            return
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            last_error = error
            if attempt < attempts:
                time.sleep(2 * attempt)
    raise SystemExit(f"cannot download {url}: {last_error}")


def sha1_file(path: Path) -> str:
    digest = hashlib.sha1()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def maven_path(coordinate: str) -> Path:
    parts = coordinate.split(":")
    if len(parts) < 3:
        raise SystemExit(f"invalid maven coordinate: {coordinate}")
    group, artifact, version = parts[0], parts[1], parts[2]
    classifier = f"-{parts[3]}" if len(parts) > 3 else ""
    return Path(*group.split(".")) / artifact / version / f"{artifact}-{version}{classifier}.jar"


def resolve_vanilla(version: str) -> dict:
    manifest = json.loads(fetch(VERSION_MANIFEST))
    entry = next((item for item in manifest["versions"] if item["id"] == version), None)
    if entry is None:
        raise SystemExit(f"Minecraft {version} is not in the official version manifest")
    return json.loads(fetch(entry["url"]))


def resolve_fabric_api(base: str, minecraft: str) -> str:
    exact = f"{base}+{minecraft}"
    probe = f"{FABRIC_MAVEN}/net/fabricmc/fabric-api/fabric-api/{exact}/fabric-api-{exact}.jar"
    request = urllib.request.Request(probe, method="HEAD", headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=60):
            return exact
    except urllib.error.HTTPError:
        pass
    metadata = fetch(f"{FABRIC_MAVEN}/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml").decode()
    versions = []
    marker = "<version>"
    while marker in metadata:
        metadata = metadata[metadata.index(marker) + len(marker):]
        versions.append(metadata[: metadata.index("</version>")])
    matches = [item for item in versions if item.startswith(base + "+") and f"+{minecraft}" in item]
    if not matches:
        raise SystemExit(f"no Fabric API {base} build for Minecraft {minecraft}")
    return sorted(matches)[-1]


def merge_libraries(vanilla: dict, fabric: dict) -> list[dict]:
    """Merge the vanilla launcher libraries with the Fabric profile libraries.

    The launcher normally merges these; build.py reads a single version JSON.
    Fabric entries win on conflict (same group:artifact) because the loader pins
    the versions it needs; a name seen twice is downloaded once.
    """
    merged: list[dict] = []
    seen: set[str] = set()
    for source in (fabric.get("libraries", []), vanilla.get("libraries", [])):
        for library in source:
            name = library.get("name") or ""
            parts = name.split(":")
            key = ":".join(parts[:2]) if len(parts) >= 2 else name
            if not key or key in seen:
                continue
            seen.add(key)
            merged.append(library)
    return merged


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft-dir", required=True)
    parser.add_argument("--version", default="26.2")
    parser.add_argument("--loader", default="0.19.5")
    parser.add_argument("--profile", default="")
    parser.add_argument("--fabric-api", default="0.161.0")
    args = parser.parse_args()

    minecraft_dir = Path(args.minecraft_dir).resolve()
    profile = args.profile or f"{args.version}-Fabric"
    version_dir = minecraft_dir / "versions" / profile
    profile_json = version_dir / f"{profile}.json"
    client_jar = version_dir / f"{profile}.jar"

    vanilla = resolve_vanilla(args.version)
    client = (vanilla.get("downloads") or {}).get("client")
    if not client or not client.get("url"):
        raise SystemExit(f"no client jar in the {args.version} version JSON")
    download(client["url"], client_jar, sha1=client.get("sha1", ""))

    fabric_meta = json.loads(fetch(f"{FABRIC_META}/{args.version}/{args.loader}/profile/json"))
    profile_json.parent.mkdir(parents=True, exist_ok=True)

    libraries = merge_libraries(vanilla, fabric_meta)
    merged_profile = dict(fabric_meta)
    merged_profile["libraries"] = libraries
    profile_json.write_text(json.dumps(merged_profile, indent=2) + "\n", encoding="utf-8")
    print(f"wrote      {profile_json} ({len(libraries)} libraries)")

    downloaded = 0
    for library in libraries:
        downloads = library.get("downloads") or {}
        artifact = downloads.get("artifact") or {}
        if artifact.get("url"):
            relative = Path(artifact.get("path") or maven_path(library["name"]))
            download(artifact["url"], minecraft_dir / "libraries" / relative, sha1=artifact.get("sha1", ""))
            downloaded += 1
            continue
        name = library.get("name")
        url = library.get("url") or FABRIC_MAVEN
        if not name:
            continue
        relative = maven_path(name)
        download(url.rstrip("/") + "/" + relative.as_posix(), minecraft_dir / "libraries" / relative)
        downloaded += 1

    api_version = resolve_fabric_api(args.fabric_api, args.version)
    api_relative = maven_path(f"net.fabricmc.fabric-api:fabric-api:{api_version}")
    api_jar = minecraft_dir / "mods" / api_relative.name
    download(
        f"{FABRIC_MAVEN}/{api_relative.as_posix()}",
        api_jar,
        sha1="",
    )

    summary = {
        "minecraft": args.version,
        "profile": profile,
        "loader": args.loader,
        "clientJar": str(client_jar),
        "clientSha1": sha1_file(client_jar),
        "libraries": downloaded,
        "fabricApi": api_version,
        "fabricApiJar": str(api_jar),
    }
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
