# SurfCanvas – Copilot Instructions

## What this project is

SurfCanvas is a Minecraft server fork that extends **Canvas** (itself built on Folia → Paper). Changes are expressed
entirely as **patch files** managed by the `io.canvasmc.weaver.patcher` Gradle plugin. You never edit generated source
directories directly; you edit the applied-patch working trees and then rebuild the patches.

## Patch layer hierarchy

```
Paper → Folia → Canvas → SurfCanvas
```

Patch directories inside `surf-canvas-server/` and `surf-canvas-api/`:

| Directory            | What it patches                           |
|----------------------|-------------------------------------------|
| `paper-patches/`     | Paper source                              |
| `folia-patches/`     | Folia source                              |
| `canvas-patches/`    | Canvas source                             |
| `minecraft-patches/` | Decompiled Minecraft source (server only) |

Each patch directory contains subdirectories:

- `features/` – sequentially-numbered git-history patches (`0001-…patch`)
- `files/` – single-file patches (path mirrors the source tree)
- `sources/` – Minecraft sources (in `minecraft-patches/` only)
- `base/` – base patches

After `applyAllPatches`, generated working trees appear at the repo root: `canvas-api/`, `canvas-server/`, `paper-api/`,
`paper-server/`, `folia-api/`, `folia-server/`.

## Key Gradle tasks

```bash
# First-time / upstream update setup
./gradlew applyAllPatches

# Build the distributable server JAR (Paperclip)
./gradlew createMojmapPaperclipJar

# Rebuild all patches after editing generated sources
./gradlew rebuildPatches

# Rebuild only minecraft (decompiled-source) patches
./gradlew fixupMinecraftFilePatches

# Compile check without a full build
./gradlew compileJava

# Rebuild Canvas file patches after editing canvas-api/ or canvas-server/
./rebuildPatches.sh               # detects changes automatically
./rebuildPatches.sh --force       # rebuild regardless of git diff
```

## Editing patches

1. Run `./gradlew applyAllPatches` to populate the working trees.
2. Edit files inside the generated directories (e.g., `canvas-server/src/…`, `paper-server/src/…`).
3. Run `./gradlew rebuildPatches` (or the task specific to the layer you changed) to write changes back into the patch
   files under `surf-canvas-server/` or `surf-canvas-api/`.
4. Commit the updated `.patch` files.

For single-file patches (Canvas layer), `./rebuildPatches.sh` handles `fixup` + `rebuild` in the right order.

## Patch comment convention

All SurfCanvas changes inside patch files must be annotated:

```java
// SurfCanvas - <short description>
code here
// SurfCanvas end
```

Single-line changes use just the opening comment. This mirrors the existing Canvas/Folia/Paper convention and makes it
easy to grep for our changes.

## Project structure & conventions

- **Java 25** with GraalVM JDK (set in `build.gradle.kts` toolchain and CI).
- **Group**: `dev.slne.surf`; **Minecraft version** and **Canvas upstream ref** live in `gradle.properties`.
- **Version format**: `{mcVersion}.build.{buildNumber}-{channel}` (e.g., `1.21.11.build.42-stable`).
- Directories named `*-debug` or `*-plugin` at the repo root are automatically included as Gradle subprojects and
  receive the Paper plugin convention (plugin YAML generation, `compileOnly` dep on `surf-canvas-server`).
- Publishing targets `https://reposilite.slne.dev/releases/` using env vars `SLNE_RELEASES_REPO_USERNAME` and
  `SLNE_RELEASES_REPO_PASSWORD`.

## Upstream tracking

The upstream Canvas commit is pinned in `gradle.properties` (`canvasCommit`, `canvasBranch`). A daily GitHub Actions
workflow (`upstream.yml`) checks for new Canvas commits, updates the properties, re-applies patches, and commits
automatically—or opens a `upstream-update` issue if patches fail to apply.
