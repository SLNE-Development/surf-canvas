package dev.slne.surf.testplugin;

import ca.spottedleaf.moonrise.common.PlatformHooks;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import io.canvasmc.canvas.region.WorldRegionizer;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deterministic reproducer for the Folia region-threading crash
 * "Thread failed main thread check: Async chunk retrieval" thrown from
 * RegionizedPlayerChunkLoader$PlayerChunkLoaderData.sendUnloadChunkRaw.
 *
 * <p>Mechanism being reproduced:
 * <ol>
 *   <li>A player's region R also owns the sections around a far destination B (connected through a
 *       chain of sections that is dead but not yet removed from R).</li>
 *   <li>The player is teleported to B. Because R owns B, Entity#teleportAsync takes the synchronous
 *       same-region path and the player's chunk loader is NOT rebuilt: its area maps still point at
 *       the old position and its sent-chunk set still contains the old chunks.</li>
 *   <li>At the end of that tick R has a pending merge (a new section was created next to it), so the
 *       regioniser removes the dead sections and recalculates connectivity: R splits into R_A (old
 *       chunks) and R_B (player).</li>
 *   <li>On the next tick of R_B the chunk loader runs update(): the area map's "teleported" branch
 *       unloads every old chunk. With a PlayerChunkUnloadEvent listener registered,
 *       sendUnloadChunkRaw calls CraftWorld#getChunkAt for a chunk owned by R_A, the Folia thread
 *       check fails and the server halts.</li>
 * </ol>
 *
 * <p>The plugin builds exactly that geometry with plugin chunk tickets, verifies every precondition
 * from the regioniser's own state, then fires the teleport and the merge trigger from a
 * ServerTickEndEvent handler on the player's region thread.
 *
 * <p>Self-test mode ({@code -Dregionsplit.selftest=true}, or {@code /regionsplit selftest}) runs the
 * same geometry without a player and verifies that the region really splits at the release of the
 * tick in which the trigger section is created.
 */
public final class RegionSplitReproducer extends JavaPlugin implements Listener {

    private static final String TAG = "[REPRO]";
    private static final long POLL_PERIOD_TICKS = 10L;
    private static final long PHASE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(150);

    private enum Phase { WAIT_A, WAIT_MERGE, WAIT_DEAD, ARMED, TRIGGERED, DONE, FAILED }

    private record CPos(int x, int z) {
        @Override
        public String toString() {
            return "[" + this.x + ", " + this.z + "]";
        }
    }

    private record SplitPrediction(boolean valid, boolean wouldSplit, int sections, int dead, String note) {}

    private static final Field SECTION_BY_KEY_FIELD;
    private static final Field DEAD_SECTIONS_FIELD;
    private static final Field EXPECTING_MERGE_FROM_FIELD;
    private static final Field MERGE_INTO_LATER_FIELD;
    private static final Throwable REFLECTION_FAILURE;

    static {
        Field sections = null;
        Field dead = null;
        Field expecting = null;
        Field mergeLater = null;
        Throwable failure = null;
        try {
            sections = ThreadedRegionizer.ThreadedRegion.class.getDeclaredField("sectionByKey");
            sections.setAccessible(true);
            dead = ThreadedRegionizer.ThreadedRegion.class.getDeclaredField("deadSections");
            dead.setAccessible(true);
            expecting = ThreadedRegionizer.ThreadedRegion.class.getDeclaredField("expectingMergeFrom");
            expecting.setAccessible(true);
            mergeLater = ThreadedRegionizer.ThreadedRegion.class.getDeclaredField("mergeIntoLater");
            mergeLater.setAccessible(true);
        } catch (final Throwable t) {
            failure = t;
        }
        SECTION_BY_KEY_FIELD = sections;
        DEAD_SECTIONS_FIELD = dead;
        EXPECTING_MERGE_FROM_FIELD = expecting;
        MERGE_INTO_LATER_FIELD = mergeLater;
        REFLECTION_FAILURE = failure;
    }

    /**
     * Pending merge bookkeeping of a region, read without the regioniser lock (diagnostic only).
     */
    private String pendingMerges(final WorldRegionizer.ChunkRegion region) {
        if (region == null || REFLECTION_FAILURE != null) {
            return "n/a";
        }
        try {
            final java.util.Collection<?> expecting = (java.util.Collection<?>) EXPECTING_MERGE_FROM_FIELD.get(region);
            final java.util.Collection<?> later = (java.util.Collection<?>) MERGE_INTO_LATER_FIELD.get(region);
            return "expectingMergeFrom=" + expecting.size() + ", mergeIntoLater=" + later.size();
        } catch (final Throwable thrown) {
            return "unreadable (" + thrown + ")";
        }
    }

    /**
     * Creates the trigger blob inside the current tick session and reports whether the ticking region now has a
     * pending merge, which is what forces the recalculation at the session's release.
     */
    private boolean applyTrigger(final Scenario current, final WorldRegionizer.ChunkRegion ticking) {
        final boolean added = current.world.addPluginChunkTicket(current.trigger.x(), current.trigger.z(), this);
        current.level.moonrise$getChunkTaskScheduler().chunkHolderManager.processTicketUpdates();
        final boolean holder = this.holderExists(current, current.trigger);
        final WorldRegionizer.ChunkRegion triggerRegion = this.regionAt(current, current.trigger);
        final String pending = this.pendingMerges(ticking);
        this.log.info("{} trigger ticket at chunk {}: added={}, holder created now={}, trigger region={}, ticking region #{} {}",
            TAG, current.trigger, added, holder, this.describe(triggerRegion), ticking == null ? -1 : ticking.getId(), pending);
        return pending.contains("expectingMergeFrom=") && !pending.contains("expectingMergeFrom=0,");
    }

    private final class Scenario {
        final World world;
        final ServerLevel level;
        final WorldRegionizer regionizer;
        final UUID playerId; // null => self-test without a player
        final CPos anchor;
        final int shift;
        final int createRadius;
        final int mergeRadius;
        final int holderRadius; // radius (in sections) of chunk holders created around a single FULL-level ticket

        volatile int aEdgeSection;
        volatile CPos bridge;
        volatile CPos target;
        volatile CPos trigger;
        // extra ticket blobs beyond B that keep the dead-section fraction under the regioniser's auto-recalculation threshold
        final java.util.List<CPos> padding = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile boolean paddingPlaced;
        volatile long mergedRegionId = -1L;
        volatile long triggeredAtNanos;
        volatile long lastProgressLogNanos;
        volatile ScheduledTask poller;

        private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.WAIT_A);
        private volatile long phaseStartNanos = System.nanoTime();

        Scenario(final World world, final UUID playerId, final CPos anchor) {
            this.world = world;
            this.level = ((CraftWorld) world).getHandle();
            this.regionizer = world.getRegionizer();
            this.playerId = playerId;
            this.anchor = anchor;
            this.shift = this.regionizer.getSectionShift();
            this.createRadius = this.regionizer.getEmptySectionCreateRadius();
            this.mergeRadius = this.regionizer.getRegionSectionMergeRadius();
            final int holderRadiusChunks = ChunkLevel.MAX_LEVEL - ChunkMap.FORCED_TICKET_LEVEL;
            this.holderRadius = (holderRadiusChunks + (1 << this.shift) - 1) >> this.shift;
        }

        boolean selfTest() {
            return this.playerId == null;
        }

        Phase phase() {
            return this.phase.get();
        }

        boolean transition(final Phase from, final Phase to) {
            if (!this.phase.compareAndSet(from, to)) {
                return false;
            }
            this.phaseStartNanos = System.nanoTime();
            return true;
        }

        boolean phaseTimedOut() {
            return System.nanoTime() - this.phaseStartNanos > PHASE_TIMEOUT_NANOS;
        }

        boolean shouldLogProgress() {
            final long now = System.nanoTime();
            if (now - this.lastProgressLogNanos > TimeUnit.SECONDS.toNanos(5)) {
                this.lastProgressLogNanos = now;
                return true;
            }
            return false;
        }
    }

    private final AtomicReference<Scenario> scenario = new AtomicReference<>();
    private final AtomicInteger unloadEvents = new AtomicInteger();
    private Logger log;

    @Override
    public void onEnable() {
        this.log = this.getSLF4JLogger();
        this.getServer().getPluginManager().registerEvents(this, this);
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
            event.registrar().register("regionsplit", "Folia region split + PlayerChunkUnloadEvent crash reproducer", new ReproCommand()));
        if (REFLECTION_FAILURE != null) {
            this.log.warn("{} cannot access ThreadedRegion internals, split prediction disabled", TAG, REFLECTION_FAILURE);
        }
        this.log.info("{} enabled. PlayerChunkUnloadEvent listeners registered: {} (this plugin registers one, which is required for the crash)",
            TAG, PlayerChunkUnloadEvent.getHandlerList().getRegisteredListeners().length);
    }

    @Override
    public void onDisable() {
        this.abort("plugin disable");
        final ReproBot current = this.bot;
        if (current != null) {
            current.close();
        }
    }

    // ---------------------------------------------------------------- listeners

    @EventHandler
    public void onServerLoad(final ServerLoadEvent event) {
        if (event.getType() != ServerLoadEvent.LoadType.STARTUP) {
            return;
        }
        if (Boolean.getBoolean("regionsplit.bot")) {
            this.log.info("{} bot run requested by system property, connecting a headless player in 3s", TAG);
            Bukkit.getGlobalRegionScheduler().runDelayed(this, _ -> this.startBotRun(), 60L);
            return;
        }
        if (!Boolean.getBoolean("regionsplit.selftest")) {
            return;
        }
        final World world = Bukkit.getWorlds().getFirst();
        final int chunkX = Integer.getInteger("regionsplit.selftest.chunkX", 0);
        final int chunkZ = Integer.getInteger("regionsplit.selftest.chunkZ", 400);
        this.log.info("{} self-test requested by system property, starting in world '{}' at chunk [{}, {}] in 5s", TAG, world.getName(), chunkX, chunkZ);
        Bukkit.getGlobalRegionScheduler().runDelayed(this, _ -> this.startSelfTest(world, chunkX, chunkZ), 100L);
    }

    // ---------------------------------------------------------------- headless bot run

    private volatile ReproBot bot;

    /**
     * Connects a headless client, waits until its chunk loader has received chunks, then runs the player
     * scenario against it. The outcome is the real crash (RED) or {@code PASSED: GREEN} on the patched build.
     */
    private void startBotRun() {
        final ReproBot created = new ReproBot(this.log, "ReproBot", Bukkit.getPort());
        this.bot = created;
        try {
            created.connect();
        } catch (final Throwable thrown) {
            this.log.error("{} bot could not connect", TAG, thrown);
            return;
        }
        final long startedAt = System.nanoTime();
        final long[] joinedAt = { 0L };
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            final Player player = Bukkit.getPlayerExact("ReproBot");
            final var loader = player == null ? null : ((CraftPlayer) player).getHandle().moonrise$getChunkLoader();
            final int sent = loader == null ? -1 : loader.getSentChunksRaw().size();
            if (player != null && joinedAt[0] == 0L) {
                joinedAt[0] = System.nanoTime();
                this.log.info("{} bot is in the world at chunk [{}, {}]", TAG, player.getLocation().getBlockX() >> 4, player.getLocation().getBlockZ() >> 4);
            }
            if (sent >= 9 && System.nanoTime() - joinedAt[0] > TimeUnit.SECONDS.toNanos(5)) {
                task.cancel();
                this.log.info("{} bot received {} chunks, starting the player scenario", TAG, sent);
                this.startPlayerRun(player, Bukkit.getConsoleSender());
                return;
            }
            if (System.nanoTime() - startedAt > TimeUnit.SECONDS.toNanos(90)) {
                task.cancel();
                this.log.error("{} bot never became a ready player (online={}, sentChunks={})", TAG, player != null, sent);
                if (Boolean.getBoolean("regionsplit.shutdown")) {
                    Bukkit.shutdown();
                }
            }
        }, 20L, 20L);
    }

    /** Unload events whose chunk was not owned by the region thread that fired them: the exact condition that crashes the unpatched build. */
    private final AtomicInteger foreignUnloads = new AtomicInteger();

    @EventHandler
    public void onPlayerChunkUnload(final PlayerChunkUnloadEvent event) {
        final int count = this.unloadEvents.incrementAndGet();
        final int chunkX = event.getChunk().getX();
        final int chunkZ = event.getChunk().getZ();
        final boolean owned = TickThread.isTickThreadFor(((CraftWorld) event.getWorld()).getHandle(), chunkX, chunkZ);
        final int foreign = owned ? this.foreignUnloads.get() : this.foreignUnloads.incrementAndGet();
        if (count <= 3 || (!owned && foreign <= 5)) {
            this.log.info("{} PlayerChunkUnloadEvent #{} player={} chunk=[{}, {}] thread={} ownedByCurrentRegion={}{}", TAG, count, event.getPlayer().getName(),
                chunkX, chunkZ, Thread.currentThread().getName(), owned, owned ? "" : " <-- this call would have halted the unpatched build");
        }
    }

    @EventHandler
    public void onTickEnd(final ServerTickEndEvent event) {
        final Scenario current = this.scenario.get();
        if (current == null || current.selfTest() || current.phase() != Phase.ARMED) {
            return;
        }
        final Player player = Bukkit.getPlayer(current.playerId);
        if (player == null) {
            this.fail(current, "player left the server");
            return;
        }
        if (!Bukkit.isOwnedByCurrentRegion(player)) {
            return; // another region's tick end, wait for the player's region
        }
        if (!current.transition(Phase.ARMED, Phase.TRIGGERED)) {
            return;
        }
        this.fire(current, player);
    }

    // ---------------------------------------------------------------- scenario control

    private void startPlayerRun(final Player player, final CommandSender feedback) {
        player.getScheduler().run(this, _ -> {
            final Location location = player.getLocation();
            final CPos anchor = new CPos(location.getBlockX() >> 4, location.getBlockZ() >> 4);
            final Scenario created = new Scenario(player.getWorld(), player.getUniqueId(), anchor);
            if (!this.install(created, feedback)) {
                return;
            }
            this.log.info("{} player run started for {} in '{}' at chunk {}. Do NOT move until the plugin reports ARMED or FAILED.",
                TAG, player.getName(), created.world.getName(), anchor);
            feedback.sendMessage(TAG + " started; stand still and watch the console");
        }, null);
    }

    private void startSelfTest(final World world, final int chunkX, final int chunkZ) {
        final Scenario created = new Scenario(world, null, new CPos(chunkX, chunkZ));
        if (!this.install(created, Bukkit.getConsoleSender())) {
            return;
        }
        world.addPluginChunkTicket(chunkX, chunkZ, this);
        this.processTicketUpdatesIfAllowed(created);
        this.log.info("{} self-test started in '{}' anchored at chunk {}", TAG, world.getName(), created.anchor);
    }

    private boolean install(final Scenario created, final CommandSender feedback) {
        final Scenario existing = this.scenario.get();
        if (existing != null && existing.phase() != Phase.DONE && existing.phase() != Phase.FAILED) {
            feedback.sendMessage(TAG + " a scenario is already running (" + existing.phase() + "), use /regionsplit abort first");
            return false;
        }
        if (!this.scenario.compareAndSet(existing, created)) {
            feedback.sendMessage(TAG + " concurrent start, try again");
            return false;
        }
        this.log.info("{} geometry parameters: sectionShift={} ({}x{} chunks per section), emptySectionCreateRadius={}, mergeRadius={}, holderRadius={} sections ({} chunks)",
            TAG, created.shift, 1 << created.shift, 1 << created.shift, created.createRadius, created.mergeRadius, created.holderRadius,
            ChunkLevel.MAX_LEVEL - ChunkMap.FORCED_TICKET_LEVEL);
        created.poller = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, _ -> this.poll(created), 20L, POLL_PERIOD_TICKS);
        return true;
    }

    private void abort(final String reason) {
        final Scenario current = this.scenario.getAndSet(null);
        if (current == null) {
            return;
        }
        this.cleanup(current);
        this.log.info("{} scenario aborted ({})", TAG, reason);
    }

    private void cleanup(final Scenario current) {
        final ScheduledTask poller = current.poller;
        if (poller != null) {
            poller.cancel();
        }
        if (this.isEnabled()) {
            current.world.removePluginChunkTickets(this);
        }
    }

    private void fail(final Scenario current, final String message) {
        final Phase previous = current.phase.getAndSet(Phase.FAILED);
        if (previous == Phase.FAILED || previous == Phase.DONE) {
            return;
        }
        this.log.error("{} FAILED in phase {}: {}", TAG, previous, message);
        this.finish(current, false);
    }

    private void pass(final Scenario current, final String message) {
        final Phase previous = current.phase.getAndSet(Phase.DONE);
        if (previous == Phase.FAILED || previous == Phase.DONE) {
            return;
        }
        this.log.info("{} PASSED: {}", TAG, message);
        this.finish(current, true);
    }

    private void finish(final Scenario current, final boolean passed) {
        this.cleanup(current);
        if (current.selfTest()) {
            this.log.info("{} SELFTEST RESULT: {}", TAG, passed ? "PASS" : "FAIL");
        } else {
            this.log.info("{} PLAYER RUN RESULT: {}", TAG, passed ? "GREEN" : "NOT GREEN");
        }
        if (Boolean.getBoolean("regionsplit.selftest.shutdown") || Boolean.getBoolean("regionsplit.shutdown")) {
            Bukkit.getGlobalRegionScheduler().runDelayed(this, _ -> Bukkit.shutdown(), 40L);
        }
    }

    // ---------------------------------------------------------------- polling state machine (global region thread)

    private void poll(final Scenario current) {
        try {
            switch (current.phase()) {
                case WAIT_A -> this.pollWaitA(current);
                case WAIT_MERGE -> this.pollWaitMerge(current);
                case WAIT_DEAD -> this.pollWaitDead(current);
                case ARMED -> {
                    if (current.phaseTimedOut()) {
                        this.fail(current, "armed but the player's region never reached a tick end (is the player online and in the world?)");
                    }
                }
                case TRIGGERED -> this.pollTriggered(current);
                case DONE, FAILED -> {
                    final ScheduledTask poller = current.poller;
                    if (poller != null) {
                        poller.cancel();
                    }
                }
            }
        } catch (final Throwable thrown) {
            this.log.warn("{} poll failed in phase {}", TAG, current.phase(), thrown);
        }
    }

    private void pollWaitA(final Scenario current) {
        final WorldRegionizer.ChunkRegion regionA = this.regionAt(current, current.anchor);
        final boolean anchorLoaded = current.level.moonrise$getFullChunkIfLoaded(current.anchor.x(), current.anchor.z()) != null;
        if (regionA == null || !anchorLoaded) {
            if (current.phaseTimedOut()) {
                this.fail(current, "anchor chunk " + current.anchor + " never got a region/full chunk (region=" + regionA + ", loaded=" + anchorLoaded + ")");
            }
            return;
        }

        // getOwnedPackedChunkPositions() returns the untrimmed backing array (spare entries read as chunk [0, 0]),
        // so use the size-aware list from the server-side region object.
        final it.unimi.dsi.fastutil.longs.LongArrayList owned = ((ThreadedRegionizer.ThreadedRegion<?, ?>) regionA).getOwnedChunks();
        if (owned.isEmpty()) {
            return;
        }
        final int c = current.createRadius;
        final int r = current.holderRadius;
        final int anchorSectionX = current.anchor.x() >> current.shift;
        // Only rows inside the bridge footprint (anchor column +- holder radius + buffer) matter for merging and for the
        // dead rows; a farther row at an unrelated x would misplace the bridge.
        int maxSectionZ = Integer.MIN_VALUE;
        int minSectionZ = Integer.MAX_VALUE;
        int globalMaxSectionZ = Integer.MIN_VALUE;
        for (int i = 0, len = owned.size(); i < len; ++i) {
            final long key = owned.getLong(i);
            final int sectionX = CoordinateUtils.getChunkX(key) >> current.shift;
            final int sectionZ = CoordinateUtils.getChunkZ(key) >> current.shift;
            globalMaxSectionZ = Math.max(globalMaxSectionZ, sectionZ);
            if (Math.abs(sectionX - anchorSectionX) > r + c) {
                continue;
            }
            maxSectionZ = Math.max(maxSectionZ, sectionZ);
            minSectionZ = Math.min(minSectionZ, sectionZ);
        }
        if (maxSectionZ == Integer.MIN_VALUE) {
            return;
        }
        if (globalMaxSectionZ > maxSectionZ) {
            this.log.warn("{} region A extends to section z {} outside the bridge column (edge used: {}); if those rows lie within {} sections of the bridge the split may not happen",
                TAG, globalMaxSectionZ, maxSectionZ, c);
        }
        // Bridge: its nearest holder row is one section past A's empty buffer, so it merges with A at creation
        // (search radius is createRadius + mergeRadius) and every one of its exclusive rows dies when it unloads.
        final int bridgeSectionZ = maxSectionZ + r + c + 1;
        // Target: far enough that after the dead rows are removed the two sides are more than max(mergeRadius, createRadius)
        // apart (gap = c + 2), yet close enough that the bridge holders connect both sides while alive.
        final int targetSectionZ = maxSectionZ + r + 3 * c + 2;
        // Trigger: a blob centred within createRadius + mergeRadius of B's buffer edge, so it merges with the (ticking)
        // region while still creating brand new sections on its far side.
        final int triggerSectionX = anchorSectionX + r + 2 * c + current.mergeRadius;

        current.aEdgeSection = maxSectionZ;
        current.bridge = new CPos(current.anchor.x(), bridgeSectionZ << current.shift);
        current.target = new CPos(current.anchor.x(), targetSectionZ << current.shift);
        current.trigger = new CPos(triggerSectionX << current.shift, targetSectionZ << current.shift);

        this.log.info("{} region A #{} owns {} chunks, section z range in the bridge column [{}..{}]. Placing bridge ticket at chunk {} (section z {}), target B at chunk {} (section z {}), trigger at chunk {}",
            TAG, regionA.getId(), owned.size(), minSectionZ, maxSectionZ, current.bridge, bridgeSectionZ, current.target, targetSectionZ, current.trigger);

        current.world.addPluginChunkTicket(current.target.x(), current.target.z(), this);
        current.world.addPluginChunkTicket(current.bridge.x(), current.bridge.z(), this);
        this.processTicketUpdatesIfAllowed(current);
        current.transition(Phase.WAIT_A, Phase.WAIT_MERGE);
    }

    private void pollWaitMerge(final Scenario current) {
        final WorldRegionizer.ChunkRegion regionA = this.regionAt(current, current.anchor);
        final WorldRegionizer.ChunkRegion regionX = this.regionAt(current, current.bridge);
        final WorldRegionizer.ChunkRegion regionB = this.regionAt(current, current.target);
        final boolean bridgeLoaded = current.level.moonrise$getFullChunkIfLoaded(current.bridge.x(), current.bridge.z()) != null;
        final boolean targetLoaded = current.level.moonrise$getFullChunkIfLoaded(current.target.x(), current.target.z()) != null;
        final boolean merged = regionA != null && regionX != null && regionB != null
            && regionA.getId() == regionX.getId() && regionA.getId() == regionB.getId();

        if (merged && bridgeLoaded && targetLoaded) {
            if (!current.paddingPlaced) {
                this.placePadding(current, regionA);
                return;
            }
            for (final CPos pad : current.padding) {
                final WorldRegionizer.ChunkRegion regionPad = this.regionAt(current, pad);
                if (regionPad == null || regionPad.getId() != regionA.getId() || current.level.moonrise$getFullChunkIfLoaded(pad.x(), pad.z()) == null) {
                    if (current.shouldLogProgress()) {
                        this.log.info("{} waiting for padding chunk {} to merge into region #{} (currently {})", TAG, pad, regionA.getId(), this.describe(regionPad));
                    }
                    if (current.phaseTimedOut()) {
                        this.fail(current, "padding blob " + pad + " never merged into the region");
                    }
                    return;
                }
            }
            current.mergedRegionId = regionA.getId();
            current.world.removePluginChunkTicket(current.bridge.x(), current.bridge.z(), this);
            this.log.info("{} A, bridge, B and {} padding blobs are one region #{} ({} chunks, {}). Bridge ticket removed; waiting for its holders to unload and its sections to die",
                TAG, current.padding.size(), regionA.getId(), ((ThreadedRegionizer.ThreadedRegion<?, ?>) regionA).getOwnedChunks().size(), this.predictSplit(current, regionA));
            current.transition(Phase.WAIT_MERGE, Phase.WAIT_DEAD);
            return;
        }
        if (current.shouldLogProgress()) {
            this.log.info("{} waiting for merge: A={} bridge={} B={} bridgeLoaded={} targetLoaded={}",
                TAG, this.describe(regionA), this.describe(regionX), this.describe(regionB), bridgeLoaded, targetLoaded);
        }
        if (current.phaseTimedOut()) {
            this.fail(current, "A, bridge and B did not become one region in time");
        }
    }

    /**
     * The regioniser recalculates a region on its own once at least minSectionRecalcCount sections exist and the dead
     * fraction reaches maxDeadRegionPercent (1/6). A small region would therefore split as soon as the bridge dies,
     * before anything is armed, so extra alive blobs are added beyond B until the expected dead fraction is well below
     * the threshold. Production regions (2x2 sections, thousands of sections) never need this.
     */
    private void placePadding(final Scenario current, final WorldRegionizer.ChunkRegion merged) {
        final int c = current.createRadius;
        final int r = current.holderRadius;
        // Regions only merge when a NEW section appears within createRadius + mergeRadius of another region's sections,
        // so each blob centre must sit within that reach of the previous blob's buffer edge.
        final int spacing = r + 2 * c + current.mergeRadius;
        final int blobWidth = 2 * (r + c) + 1;
        final int expectedDead = (c + 1) * blobWidth;
        final SplitPrediction prediction = this.predictSplit(current, merged);
        final int currentSections = prediction.valid() ? prediction.sections() : 0;
        final double targetFraction = current.regionizer.getMaxDeadRegionPercent() * 0.5D;
        final int neededSections = (int) Math.ceil(expectedDead / targetFraction) - currentSections;
        final int newSectionsPerBlob = blobWidth * spacing;
        final int blobs = neededSections <= 0 ? 0 : (int) Math.ceil(neededSections / (double) newSectionsPerBlob);
        final int targetSectionZ = current.target.z() >> current.shift;
        for (int k = 1; k <= blobs; ++k) {
            final CPos pad = new CPos(current.anchor.x(), (targetSectionZ + k * spacing) << current.shift);
            current.padding.add(pad);
            current.world.addPluginChunkTicket(pad.x(), pad.z(), this);
        }
        current.paddingPlaced = true;
        this.processTicketUpdatesIfAllowed(current);
        this.log.info("{} merged region #{} has {} sections; the dead bridge will add about {} dead sections. Target dead fraction {} => {} padding blob(s) beyond B: {}",
            TAG, merged.getId(), currentSections, expectedDead, String.format(Locale.ROOT, "%.2f", targetFraction), blobs, current.padding);
    }

    private void pollWaitDead(final Scenario current) {
        final WorldRegionizer.ChunkRegion regionA = this.regionAt(current, current.anchor);
        final WorldRegionizer.ChunkRegion regionB = this.regionAt(current, current.target);
        if (regionA == null || regionB == null) {
            this.fail(current, "region vanished while waiting for the bridge to die (A=" + this.describe(regionA) + ", B=" + this.describe(regionB) + ")");
            return;
        }
        if (regionA.getId() != regionB.getId()) {
            this.fail(current, "region split BEFORE arming (A #" + regionA.getId() + ", B #" + regionB.getId() + "). A recalculation happened early: "
                + "either the dead fraction reached the regioniser threshold (" + current.regionizer.getMaxDeadRegionPercent() + ") despite padding, "
                + "or something created new sections next to the region (a moving player). Retry and stand still.");
            return;
        }
        final boolean bridgeHolderGone = !this.holderExists(current, current.bridge);
        final SplitPrediction prediction = this.predictSplit(current, regionA);
        if (bridgeHolderGone && prediction.valid() && prediction.wouldSplit()) {
            this.log.info("{} bridge holders are gone. Region #{} has {} sections of which {} are dead; A and B fall into different components once the dead sections are removed, so the next recalculation WILL split the region.",
                TAG, regionA.getId(), prediction.sections(), prediction.dead());
            if (current.selfTest()) {
                current.transition(Phase.WAIT_DEAD, Phase.TRIGGERED);
                this.triggerSelfTest(current);
            } else {
                current.transition(Phase.WAIT_DEAD, Phase.ARMED);
                this.log.info("{} ARMED. The player is teleported from chunk {} to chunk {} at the end of the next tick of their region, followed by the merge trigger. Expect the server to halt one tick later.",
                    TAG, current.anchor, current.target);
            }
            return;
        }
        if (current.shouldLogProgress()) {
            this.log.info("{} waiting for the bridge to die: bridgeHolderGone={} prediction={}", TAG, bridgeHolderGone, prediction);
        }
        if (current.phaseTimedOut()) {
            this.fail(current, "bridge never died or predictor never saw a split: bridgeHolderGone=" + bridgeHolderGone + " prediction=" + prediction);
        }
    }

    private void pollTriggered(final Scenario current) {
        final long triggeredAt = current.triggeredAtNanos;
        if (triggeredAt == 0L) {
            if (current.phaseTimedOut()) {
                this.fail(current, "trigger task never ran");
            }
            return;
        }
        final long elapsed = System.nanoTime() - triggeredAt;
        if (elapsed < TimeUnit.SECONDS.toNanos(1)) {
            return;
        }
        final WorldRegionizer.ChunkRegion regionA = this.regionAt(current, current.anchor);
        final WorldRegionizer.ChunkRegion regionB = this.regionAt(current, current.target);
        final boolean split = regionA != null && regionB != null && regionA.getId() != regionB.getId();
        if (current.selfTest()) {
            if (split) {
                this.pass(current, "region #" + current.mergedRegionId + " split into A #" + regionA.getId() + " and B #" + regionB.getId()
                    + " at the release of the tick in which the trigger section was created");
            } else if (elapsed > TimeUnit.SECONDS.toNanos(5)) {
                this.fail(current, "region did not split after the trigger: A=" + this.describe(regionA) + " B=" + this.describe(regionB));
            }
            return;
        }
        if (elapsed > TimeUnit.SECONDS.toNanos(3)) {
            final Player player = Bukkit.getPlayer(current.playerId);
            final int oldChunksStillSent = player == null ? -1 : this.countOldSentChunks(player, current);
            final int foreign = this.foreignUnloads.get();
            // Note: the teleported player's own chunk loading usually re-merges A and B within a second, so "still split"
            // is not a usable criterion; the foreign unload count is.
            final String details = "foreignUnloads=" + foreign + " oldChunksStillSent=" + oldChunksStillSent + " stillSplit=" + split
                + " A=" + this.describe(regionA) + " B=" + this.describe(regionB);
            if (foreign > 0 && oldChunksStillSent == 0) {
                this.pass(current, "GREEN: the chunk loader fired PlayerChunkUnloadEvent for chunks owned by another region and the server survived (" + details + ")");
            } else if (oldChunksStillSent == 0) {
                this.fail(current, "INCONCLUSIVE: the old chunks were unloaded while still owned by the player's region, so the region did not split in time (" + details + ")");
            } else {
                this.fail(current, "INCONCLUSIVE: the loader still tracks old chunks, the unload has not run (" + details + ")");
            }
        }
    }

    // ---------------------------------------------------------------- firing (player's region thread, end of tick)

    private void fire(final Scenario current, final Player player) {
        final ServerLevel level = current.level;
        final CPos target = current.target;
        final Location from = player.getLocation();
        final Location to = new Location(current.world, (target.x() << 4) + 8.5D, from.getY(), (target.z() << 4) + 8.5D, from.getYaw(), from.getPitch());
        final CPos playerChunk = new CPos(from.getBlockX() >> 4, from.getBlockZ() >> 4);

        final boolean destinationOwned = TickThread.isTickThreadFor(level, target.x(), target.z(), 8);
        final boolean destinationLoaded = level.moonrise$getFullChunkIfLoaded(target.x(), target.z()) != null;
        this.log.info("{} firing on {} in region #{}: player chunk {}, destination chunk {} owned by this region within 8 chunks: {}, destination loaded: {}",
            TAG, Thread.currentThread().getName(), this.currentRegionId(), playerChunk, target, destinationOwned, destinationLoaded);
        if (!destinationOwned || !destinationLoaded) {
            this.fail(current, "precondition lost: the teleport would take the cross-region path (loader rebuilt, no crash)");
            return;
        }

        final ServerPlayer handle = ((CraftPlayer) player).getHandle();
        final var loaderBefore = handle.moonrise$getChunkLoader();
        final LongOpenHashSet sentBefore = loaderBefore.getSentChunksRaw().clone();

        final CompletableFuture<Boolean> future = player.teleportAsync(to);
        final boolean completedTrue = future.isDone() && Boolean.TRUE.equals(future.getNow(Boolean.FALSE));
        final Location after = player.getLocation();
        final CPos afterChunk = new CPos(after.getBlockX() >> 4, after.getBlockZ() >> 4);
        final var loaderAfter = handle.moonrise$getChunkLoader();
        final int sentAfter = loaderAfter == null ? -1 : loaderAfter.getSentChunksRaw().size();
        final boolean synchronous = completedTrue && afterChunk.equals(target) && loaderAfter == loaderBefore && sentAfter == sentBefore.size();
        this.log.info("{} teleportAsync: completed=TRUE synchronously: {}, player chunk now {} (target {}), chunk loader instance unchanged: {}, sent chunks before/after: {}/{}",
            TAG, completedTrue, afterChunk, target, loaderAfter == loaderBefore, sentBefore.size(), sentAfter);
        if (!synchronous) {
            this.fail(current, "the teleport did not take the synchronous same-region path with the chunk loader kept intact; no crash can happen on this run");
            return;
        }

        // Create brand new sections next to the (ticking) region: they form a transient region that is scheduled to
        // merge into ours, which forces onRegionRelease to remove dead sections and recalculate (split) at tick end.
        final boolean pendingMerge = this.applyTrigger(current, this.regionAt(current, target));
        current.triggeredAtNanos = System.nanoTime();
        if (!pendingMerge) {
            this.log.warn("{} the trigger did not register a pending merge on the ticking region; the release may not recalculate and the run may end INCONCLUSIVE", TAG);
        }

        final long sampleOld = sentBefore.isEmpty() ? 0L : sentBefore.iterator().nextLong();
        this.log.info("{} trigger ticket at chunk {} processed. EXPECT on the next tick of the region owning chunk {}: "
                + "'Thread failed main thread check: Async chunk retrieval' for an old chunk such as [{}, {}], then 'A region-threading exception occurred, halting the server.'",
            TAG, current.trigger, target, CoordinateUtils.getChunkX(sampleOld), CoordinateUtils.getChunkZ(sampleOld));
    }

    private void triggerSelfTest(final Scenario current) {
        Bukkit.getRegionScheduler().execute(this, current.world, current.anchor.x(), current.anchor.z(), () -> {
            final long regionBefore = this.currentRegionId();
            final WorldRegionizer.ChunkRegion regionA = this.regionAt(current, current.anchor);
            final WorldRegionizer.ChunkRegion regionB = this.regionAt(current, current.target);
            if (regionA == null || regionB == null || regionA.getId() != regionB.getId() || regionA.getId() != regionBefore) {
                this.fail(current, "self-test: A and B were no longer one ticking region right before the trigger (A=" + this.describe(regionA)
                    + ", B=" + this.describe(regionB) + ", ticking #" + regionBefore + ")");
                return;
            }
            final boolean pendingMerge = this.applyTrigger(current, regionA);
            current.triggeredAtNanos = System.nanoTime();
            this.log.info("{} self-test trigger applied inside a task session of region #{} (pending merge registered: {}); the region should split when this session is released. Dead/sections now: {}",
                TAG, regionBefore, pendingMerge, this.predictSplit(current, regionA));
            // Verify from the global thread: a region-scheduled check can run inside this very task session (before the
            // release) once the global tick counter advances, which would be a false negative.
            this.verifySelfTestSplit(current, regionBefore, 1);
        });
    }

    private void verifySelfTestSplit(final Scenario current, final long regionBefore, final int attempt) {
        Bukkit.getGlobalRegionScheduler().runDelayed(this, _ -> {
            final WorldRegionizer.ChunkRegion nowA = this.regionAt(current, current.anchor);
            final WorldRegionizer.ChunkRegion nowB = this.regionAt(current, current.target);
            if (nowA != null && nowB != null && nowA.getId() != nowB.getId()) {
                this.pass(current, "region #" + regionBefore + " split into A " + this.describe(nowA) + " and B " + this.describe(nowB)
                    + " right after the trigger session (verified " + attempt * 2 + " global ticks later)");
                return;
            }
            final SplitPrediction state = nowA == null ? null : this.predictSplit(current, nowA);
            if (state != null && state.valid() && state.dead() == 0 && nowA.getId() == regionBefore) {
                this.fail(current, "self-test: the region was recalculated (dead sections removed) but did not split: " + state);
                return;
            }
            if (attempt >= 10) {
                this.fail(current, "self-test: no split within " + attempt * 2 + " global ticks after the trigger (A=" + this.describe(nowA)
                    + ", B=" + this.describe(nowB) + ", " + this.pendingMerges(nowA) + ", " + state + ")");
                return;
            }
            this.verifySelfTestSplit(current, regionBefore, attempt + 1);
        }, 2L);
    }

    // ---------------------------------------------------------------- helpers

    private WorldRegionizer.ChunkRegion regionAt(final Scenario current, final CPos pos) {
        return pos == null ? null : current.regionizer.getRegionAtSynchronised(pos.x(), pos.z());
    }

    private boolean holderExists(final Scenario current, final CPos pos) {
        final ChunkHolderManager manager = current.level.moonrise$getChunkTaskScheduler().chunkHolderManager;
        return manager.getChunkHolder(pos.x(), pos.z()) != null;
    }

    private void processTicketUpdatesIfAllowed(final Scenario current) {
        if (TickThread.isTickThread() || PlatformHooks.get().allowAsyncTicketUpdates()) {
            current.level.moonrise$getChunkTaskScheduler().chunkHolderManager.processTicketUpdates();
        }
    }

    private long currentRegionId() {
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region = TickRegionScheduler.getCurrentRegion();
        return region == null ? -1L : region.getId();
    }

    private String describe(final WorldRegionizer.ChunkRegion region) {
        if (region == null) {
            return "none";
        }
        return "#" + region.getId() + "(" + region.getState() + ", dead " + String.format(Locale.ROOT, "%.1f", region.getDeadSectionPercent() * 100.0D) + "%)";
    }

    /**
     * Counts sent chunks closer to the old position than to the target. Reads the loader's set without
     * synchronisation, which is acceptable for a diagnostic taken seconds after the event.
     */
    private int countOldSentChunks(final Player player, final Scenario current) {
        try {
            final ServerPlayer handle = ((CraftPlayer) player).getHandle();
            final var loader = handle.moonrise$getChunkLoader();
            if (loader == null) {
                return -1;
            }
            int oldChunks = 0;
            for (final long key : loader.getSentChunksRaw().clone()) {
                if (Math.abs(CoordinateUtils.getChunkZ(key) - current.anchor.z()) < Math.abs(CoordinateUtils.getChunkZ(key) - current.target.z())) {
                    oldChunks++;
                }
            }
            return oldChunks;
        } catch (final Throwable thrown) {
            return -1;
        }
    }

    /**
     * Re-implements the connectivity pass of ThreadedRegionizer#onRegionRelease on a snapshot of the region:
     * drop the dead sections, then BFS from A's section with the same radius the regioniser uses and
     * report whether B's section is still reachable.
     */
    @SuppressWarnings("unchecked")
    private SplitPrediction predictSplit(final Scenario current, final WorldRegionizer.ChunkRegion regionApi) {
        if (REFLECTION_FAILURE != null) {
            return new SplitPrediction(false, false, 0, 0, "reflection unavailable");
        }
        if (!(regionApi instanceof ThreadedRegionizer.ThreadedRegion<?, ?> region)) {
            return new SplitPrediction(false, false, 0, 0, "unexpected region type " + regionApi.getClass().getName());
        }
        try {
            final Long2ReferenceOpenHashMap<ThreadedRegionizer.ThreadedRegionSection<?, ?>> sections =
                (Long2ReferenceOpenHashMap<ThreadedRegionizer.ThreadedRegionSection<?, ?>>) SECTION_BY_KEY_FIELD.get(region);
            final ReferenceOpenHashSet<ThreadedRegionizer.ThreadedRegionSection<?, ?>> dead =
                (ReferenceOpenHashSet<ThreadedRegionizer.ThreadedRegionSection<?, ?>>) DEAD_SECTIONS_FIELD.get(region);

            final LongOpenHashSet alive = new LongOpenHashSet(sections.size());
            for (final Long2ReferenceMap.Entry<ThreadedRegionizer.ThreadedRegionSection<?, ?>> entry : sections.long2ReferenceEntrySet()) {
                if (!dead.contains(entry.getValue())) {
                    alive.add(entry.getLongKey());
                }
            }
            final int deadCount = sections.size() - alive.size();
            final long start = this.sectionKey(current, current.anchor);
            final long goal = this.sectionKey(current, current.target);
            if (!alive.contains(start) || !alive.contains(goal)) {
                return new SplitPrediction(false, false, sections.size(), deadCount, "A or B section is not alive in this region");
            }
            final int radius = Math.max(current.mergeRadius, current.createRadius);
            final LongOpenHashSet seen = new LongOpenHashSet();
            final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
            queue.enqueue(start);
            seen.add(start);
            while (!queue.isEmpty()) {
                final long key = queue.dequeueLong();
                if (key == goal) {
                    return new SplitPrediction(true, false, sections.size(), deadCount, "A and B still connected");
                }
                final int sx = CoordinateUtils.getChunkX(key);
                final int sz = CoordinateUtils.getChunkZ(key);
                for (int dz = -radius; dz <= radius; ++dz) {
                    for (int dx = -radius; dx <= radius; ++dx) {
                        final long neighbour = CoordinateUtils.getChunkKey(sx + dx, sz + dz);
                        if (alive.contains(neighbour) && seen.add(neighbour)) {
                            queue.enqueue(neighbour);
                        }
                    }
                }
            }
            return new SplitPrediction(true, true, sections.size(), deadCount, "A and B disconnected without dead sections");
        } catch (final Throwable thrown) {
            // the maps are read without the regioniser lock; a concurrent modification simply means "ask again"
            return new SplitPrediction(false, false, 0, 0, "snapshot failed: " + thrown);
        }
    }

    private long sectionKey(final Scenario current, final CPos pos) {
        return CoordinateUtils.getChunkKey(pos.x() >> current.shift, pos.z() >> current.shift);
    }

    // ---------------------------------------------------------------- command

    private final class ReproCommand implements BasicCommand {

        @Override
        public void execute(final CommandSourceStack source, final String[] args) {
            final CommandSender sender = source.getSender();
            final String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "run" -> {
                    final Player player = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player self ? self : null);
                    if (player == null) {
                        sender.sendMessage(TAG + " usage: /regionsplit run [player]");
                        return;
                    }
                    RegionSplitReproducer.this.startPlayerRun(player, sender);
                }
                case "selftest" -> {
                    final World world = sender instanceof Player self ? self.getWorld() : Bukkit.getWorlds().getFirst();
                    final int chunkX = args.length > 1 ? Integer.parseInt(args[1]) : 0;
                    final int chunkZ = args.length > 2 ? Integer.parseInt(args[2]) : 400;
                    sender.sendMessage(TAG + " self-test started, watch the console");
                    RegionSplitReproducer.this.startSelfTest(world, chunkX, chunkZ);
                }
                case "abort" -> {
                    RegionSplitReproducer.this.abort("command");
                    sender.sendMessage(TAG + " aborted");
                }
                case "status" -> {
                    final Scenario current = RegionSplitReproducer.this.scenario.get();
                    sender.sendMessage(TAG + " PlayerChunkUnloadEvent listeners: " + PlayerChunkUnloadEvent.getHandlerList().getRegisteredListeners().length
                        + ", unload events seen: " + RegionSplitReproducer.this.unloadEvents.get());
                    if (current == null) {
                        sender.sendMessage(TAG + " no scenario running");
                    } else {
                        sender.sendMessage(TAG + " phase=" + current.phase() + " selfTest=" + current.selfTest() + " anchor=" + current.anchor
                            + " bridge=" + current.bridge + " target=" + current.target + " trigger=" + current.trigger + " mergedRegion=#" + current.mergedRegionId);
                    }
                }
                default -> sender.sendMessage(TAG + " usage: /regionsplit <run [player]|selftest [chunkX chunkZ]|status|abort>");
            }
        }

        @Override
        public Collection<String> suggest(final CommandSourceStack source, final String[] args) {
            return args.length <= 1 ? List.of("run", "selftest", "status", "abort") : List.of();
        }
    }
}
