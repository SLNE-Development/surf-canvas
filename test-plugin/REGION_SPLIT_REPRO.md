# Region split crash reproducer

Reproduces the SurfCanvas/Folia crash

```
Thread failed main thread check: Async chunk retrieval, context=[... region={center=[6, 0],world=minecraft:the_end}], world=minecraft:the_end, chunk_pos=[7, -69]
  at ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread
  at org.bukkit.craftbukkit.CraftWorld.getChunkAt
  at ...RegionizedPlayerChunkLoader$PlayerChunkLoaderData.sendUnloadChunkRaw(RegionizedPlayerChunkLoader.java:464)
  at ...SingleUserAreaMap.update(SingleUserAreaMap.java:106)            <- "teleported" branch, removeFromOld
  at ...RegionizedPlayerChunkLoader.tick(RegionizedPlayerChunkLoader.java:315)
```

## Mechanism

1. `Entity#teleportAsync` takes the synchronous same-region path when the current region already owns
   every section within 8 chunks of the destination (`Entity#isIsSameRegion`). That path only moves the
   entity (`teleportSyncSameRegion`); the player's chunk loader is **not** torn down and rebuilt, so its
   area maps still point at the old position and `sentChunks` still holds the old chunks.
2. The next loader update happens in the next tick (`ServerChunkCache#tick` -> `RegionizedPlayerChunkLoader#tick`).
   Between the two there is a region release (`ThreadedRegionizer#onRegionRelease`). If the region has a
   pending merge or enough dead sections, dead sections are removed and connectivity is recalculated.
   When the old area and the new position were only connected through dead sections, the region splits;
   the player goes to the region owning the new position, the old chunks stay in the other region.
3. `update()` then runs the "teleported" branch of the broadcast area map and unloads every old chunk.
   With at least one `PlayerChunkUnloadEvent` listener registered, `sendUnloadChunkRaw` calls
   `CraftWorld#getChunkAt` for a chunk the current region does not own, the Folia thread check fails and
   `TickRegionScheduler` halts the server.

Without a `PlayerChunkUnloadEvent` listener the event block is skipped and nothing crashes, which is why
ordinary teleport tests on a server without such a plugin never reproduce it. Cross-region teleports never
reproduce it either, because that path removes and re-adds the player (fresh chunk loader).

The return gateway on the outer End islands is a natural trigger: it teleports to
`Vec3.atCenterOf(END_SPAWN_POINT)` = (100.5, 50.5, 0.5), exactly the position in the crash report.

## What the plugin does

`RegionSplitReproducer` builds the geometry with plugin chunk tickets and verifies every precondition
from the regioniser's own state before firing:

| Phase | Action |
| --- | --- |
| `WAIT_A` | Reads the player's region A, finds its last non-empty section row and places a *bridge* ticket and the *target* B so that A, bridge and B merge into one region. |
| `WAIT_MERGE` | Waits until A, bridge and B report the same region id and the chunks are loaded, then removes the bridge ticket. |
| `WAIT_DEAD` | Waits until the bridge holders are gone and a BFS over the region's non-dead sections (same radius the regioniser uses) shows A and B in different components. |
| `ARMED` | At the end of the next tick of the player's region: `teleportAsync` to B (must complete synchronously), then a *trigger* ticket creates new sections next to the region so the release recalculates and splits it. |
| `TRIGGERED` | The next tick of B's region runs the chunk loader update and the server halts (RED). If the server survives 3 s the plugin checks that the region really split and that the loader dropped the old chunks: `PASSED: GREEN` on the patched build, otherwise `FAILED ... INCONCLUSIVE` with the reason. |

The plugin also verifies, before declaring the teleport "synchronous", that the future completed with `true`, the
player's chunk equals the target, the chunk loader instance is unchanged and the sent-chunk set is intact. If any
of that fails it reports `FAILED` and the run proves nothing (the cross-region path was taken).

The plugin registers its own `PlayerChunkUnloadEvent` listener, so no other plugin is needed.

## Running the player reproduction (RED / GREEN)

1. Build: `./gradlew :test-plugin:jar` -> `test-plugin/build/libs/regionsplit-reproducer-1.0.0.jar`.
2. Put the jar in `plugins/` of a SurfCanvas test server (any world; `grid-exponent` 1 like production or
   the default 4 both work, the plugin reads the regioniser parameters at runtime).
3. Join, stand still, run `/regionsplit run`. The whole run takes 10 to 30 seconds; the console shows one
   `[REPRO]` line per phase.
4. RED (unpatched build): `Thread failed main thread check: Async chunk retrieval` followed by
   `A region-threading exception occurred, halting the server.`
   GREEN (patched build): `[REPRO] PASSED: GREEN: the region split as planned and the chunk loader unloaded
   the old chunks on the new region without halting the server`.
   Anything reported as `INCONCLUSIVE` means a precondition was not met; the log line says which.

`test-plugin/scripts/regionsplit-hitl.sh [path/to/latest.log]` wraps these steps and greps the log for
the signature (exit code 0 = reproduced).

If the plugin reports `region split BEFORE arming`, something created new sections next to the region
early (usually the player moved). Run `/regionsplit abort` and try again without moving.

## Fully automated run with a headless bot (no client needed)

`ReproBot` is a minimal Minecraft client built from the server's own protocol classes. It logs in over
loopback (offline mode), answers keep-alives, known packs, cookies and teleport confirmations, and is
otherwise a normal player with a chunk loader. With `-Dregionsplit.bot=true` the plugin connects it after
startup, waits until it has received chunks, runs the player scenario against it and shuts the server
down afterwards (`-Dregionsplit.shutdown=true`):

```
JAVA_TOOL_OPTIONS="-Dregionsplit.bot=true -Dregionsplit.shutdown=true" ./gradlew :surf-canvas-server:runDevServer
```

Verdict lines to grep for: `Thread failed main thread check: Async chunk retrieval` (RED),
`[REPRO] PASSED: GREEN` (fix present), `[REPRO] FAILED ... INCONCLUSIVE` (precondition not met).
The dev server needs `white-list=false` in `run/server.properties`.

Verified on 2026-10-28 with `grid-exponent: 1`:

- Unpatched build (fix lines reverted temporarily): the server halted one tick after the teleport with
  the production signature, `Region #141 centered at chunk [19, 192] ... failed to tick`, caused by
  `Async chunk retrieval` for old chunk `[-11, 122]`, stack identical to the production crash report.
- Patched build: `PASSED: GREEN`, 473 `PlayerChunkUnloadEvent`s fired for chunks the firing region did
  not own (each one would have halted the unpatched build), server survived.

Note that "A and B are still split" is not a usable success criterion for the player run: the teleported
player's own chunk loading re-merges the regions within about a second. The plugin therefore counts unload
events whose chunk is not owned by the current region thread.

## Self-test without a client

The regioniser part (merge, death of the bridge, forced split at release) can be verified without a player:

```
/regionsplit selftest [chunkX chunkZ]
```

or automatically at startup, for example with the dev server task:

```
JAVA_TOOL_OPTIONS="-Dregionsplit.selftest=true -Dregionsplit.selftest.shutdown=true" \
  ./gradlew :surf-canvas-server:runDevServer
```

The dev server uses `run/` as its working directory (`run/server.properties` is a flat, offline world on
port 25599; `run/eula.txt` is accepted; `run/config/paper-global.yml` is set to `grid-exponent: 1` like
production). The result is logged as `[REPRO] SELFTEST RESULT: PASS|FAIL` and the server shuts down
afterwards. A full run takes about 35 seconds.

Verified on 2026-10-28 (build 4c3377f plus the fix): `SELFTEST RESULT: PASS` with the default
`grid-exponent: 4` (merged region 104 sections, 8 dead) and with production's `grid-exponent: 1` (merged
region 1430 sections, 110 dead = 7.7 %, below the 1/6 auto-recalculation threshold, so the dead chain
persisted until the trigger). In both cases the region split at the release of the session in which the
trigger section was created.

Two regioniser rules the plugin had to respect, both confirmed by failed runs:

- Regions only merge when a **new** section is created within `createRadius + mergeRadius` of another
  region's sections. Adjacent buffers alone never merge. Blob centres are therefore spaced
  `holderRadius + 2 * createRadius + mergeRadius` apart.
- A region recalculates on its own once it has at least `minSectionRecalcCount` sections and at least one
  sixth of them are dead. Small test regions would split before arming, so the plugin pads the merged
  region with extra ticket blobs beyond B until the expected dead fraction is about half the threshold.

## The fix

`RegionizedPlayerChunkLoader#sendUnloadChunkRaw` and `PlayerChunkSender#dropChunk` now hand the event a
`new CraftChunk(level, x, z)` instead of `world.getChunkAt(...)`. The handle carries the same world and
coordinates, is created without any lookup and only touches the chunk system if a listener asks for block
data, so event semantics for coordinate-based listeners (anti-xray, holograms, per-player chunk caches) are
unchanged, and the unload path no longer performs a synchronous chunk load either.
