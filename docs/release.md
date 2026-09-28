# Mod release process

The interface mod ships as one Fabric jar with a client and a server entrypoint.
Releases are produced by [`.github/workflows/release.yml`](../.github/workflows/release.yml)
on a `vX.Y.Z` tag; the workflow builds the jar from public sources, runs the
unit tests, verifies the jar metadata and publishes the jar with a checksum.

## Version and compatibility

| Item | Current |
| --- | --- |
| Mod version (`fabric.mod.json`) | `0.8.0` |
| Control protocol | `1` |
| Minecraft | `26.2` |
| Fabric Loader | `0.19.5` (minimum declared `>=0.19.0`) |
| Fabric API | `0.161.0+26.2` |
| Java | `25` |

`fabric.mod.json` is the single source of the mod version. The release tag must
be `v` + that version; the workflow fails otherwise.

## Local release build

The repository does not ship a Minecraft installation. A build-only directory
can be prepared from public sources (Mojang version manifest, Fabric meta,
Fabric Maven, all SHA-1 checked where the source provides one):

```bash
python ci/prepare_minecraft.py --minecraft-dir .ci-minecraft \
    --version 26.2 --loader 0.19.5 --fabric-api 0.161.0

python test.py --minecraft-dir .ci-minecraft --version 26.2-Fabric --jdk "$JAVA_HOME"

python build.py --minecraft-dir .ci-minecraft --version 26.2-Fabric \
    --jdk "$JAVA_HOME" --output dist

python ci/verify_jar.py --jar dist/mc-agent-interface-0.8.0.jar \
    --version 0.8.0 --tag v0.8.0

cd dist && sha256sum mc-agent-interface-0.8.0.jar > checksums.txt
```

The prepared directory is cacheable (`libraries/`, `versions/`, `mods/`);
nothing in it is committed.

## Publishing

1. Bump `src/main/resources/fabric.mod.json` and update the compatibility table
   here and in the README.
2. Open the PR; the release workflow builds, tests and verifies the jar on the
   runner.
3. Merge, then tag `vX.Y.Z` from the merged commit. The workflow repeats the
   build and publishes a GitHub release with:
   - `mc-agent-interface-X.Y.Z.jar`
   - `checksums.txt`
4. Verify the published assets:

   ```bash
   gh release download v0.8.0 --repo guajun/mc-agent-interface-mod
   sha256sum -c checksums.txt          # Linux
   shasum -a 256 -c checksums.txt      # macOS
   ```

The release notes state the tested stack. Do not claim other Minecraft or
loader versions; the jar's metadata still allows the loader to try, but only
26.2 with the listed Fabric components is built and tested.

## User verification

```bash
sha256sum -c checksums.txt          # Linux
shasum -a 256 -c checksums.txt      # macOS
```

Then put exactly one interface jar next to Fabric API in `mods/`, start the
game/server, and check that `<gameDir>/mc-agent-server/control/fingerprint.txt`
appears when `-Dmcagent.control=true` is set. The Toolkit side is documented at
<https://guajun.github.io/mc-agent/>.
