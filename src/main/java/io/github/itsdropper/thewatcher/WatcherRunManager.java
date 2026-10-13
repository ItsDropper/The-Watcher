package io.github.itsdropper.thewatcher;

import io.github.itsdropper.thewatcher.entity.WatcherEntity;
import io.github.itsdropper.thewatcher.entity.WatcherLearningEngine;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Controls a single randomized run. During the learning stage there is deliberately
 * no Watcher entity in the world, so it cannot accidentally be spotted early.
 */
public final class WatcherRunManager {
    private static final Map<ServerWorld, RunState> RUNS = new WeakHashMap<>();
    private static final Set<ServerWorld> CHECKED_WORLDS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final long TICKS_PER_HOUR = 72_000L;
    private static final Map<ServerWorld, EndingState> ENDINGS = new WeakHashMap<>();

    private WatcherRunManager() {
    }

    public static void tick(ServerWorld world) {
        if (world.getRegistryKey() != World.OVERWORLD) {
            return;
        }

        EndingState ending = ENDINGS.get(world);
        if (ending != null) {
            tickEnding(world, ending);
            return;
        }

        RunState run = RUNS.get(world);
        if (run == null) {
            if (WatcherWorldOptions.WATCHER_MODE_ENABLED) {
                // The world-creation toggle applies to the next Overworld that starts ticking.
                run = new RunState(world.getTime(), WatcherWorldOptions.getRunLengthHours(),
                        world.random.nextInt(6_001) + 3_600L);
                run.nextTowerAt = world.getTime() + world.random.nextInt(4_801) + 2_400L;
                WatcherTowerGenerator.resetForRun();
                // Prepare the guaranteed tower during run initialization, before exploration.
                WatcherTowerGenerator.generateGuaranteedStructures(world);
                RUNS.put(world, run);
                CHECKED_WORLDS.add(world);
                WatcherWorldOptions.WATCHER_MODE_ENABLED = false;
                saveRun(world, run);
            } else {
                if (!CHECKED_WORLDS.add(world)) {
                    return;
                }
                run = loadRun(world);
                if (run == null) {
                    return;
                }
                RUNS.put(world, run);
                if (!run.ended && WatcherTowerGenerator.snapshotGuaranteedStructuresMask() != 15) {
                    // Older runs and failed placements are upgraded/retried instead of remaining incomplete.
                    WatcherTowerGenerator.generateGuaranteedStructures(world);
                    saveRun(world, run);
                }
            }
        }

        if (run.ended) {
            return;
        }

        long elapsed = Math.max(0L, world.getTime() - run.startedAt);
        if (elapsed >= run.runLengthHours * TICKS_PER_HOUR) {
            endRun(world, run);
            return;
        }

        if (WatcherTowerGenerator.snapshotGuaranteedStructuresMask() != 15
                && world.getTime() % 1_200L == 0L) {
            if (WatcherTowerGenerator.generateGuaranteedStructures(world)) saveRun(world, run);
        }

        if (run.revealed && world.getTime() % 20L == 0L) {
            ensureWatcherPresent(world, run);
        }
        WatcherOllamaDirector.tick(world, run.revealed);
        WatcherAtmosphereDirector.tick(world, run.revealed);

        if (world.getTime() % 20L == 0L) {
            samplePlayers(world, run);
        }
        if (world.getTime() % 1_200L == 0L) {
            saveRun(world, run);
        }

        if (world.getTime() >= run.nextTowerAt) {
            ServerPlayerEntity explorer = choosePlayer(world, run);
            if (explorer != null && WatcherTowerGenerator.tryGenerate(world, explorer)) {
                run.nextTowerAt = world.getTime() + world.random.nextInt(18_001) + 18_000L;
                saveRun(world, run);
            } else {
                run.nextTowerAt = world.getTime() + 6_000L;
            }
        }

        if (!run.revealed && elapsed >= run.revealDelayTicks && isNight(world)) {
            ServerPlayerEntity player = choosePlayer(world, run);
            if (player != null && spawnFirstReveal(world, player, run)) {
                run.revealed = true;
                for (ServerPlayerEntity participant : world.getPlayers()) {
                    participant.sendMessage(Text.literal("Watcher joined the game"), false);
                }
                saveRun(world, run);
            }
        }
    }

    private static void samplePlayers(ServerWorld world, RunState run) {
        WatcherEntity loadedWatcher = null;
        if (run.naturalWatcherUuid != null
                && world.getEntity(run.naturalWatcherUuid) instanceof WatcherEntity watcher
                && !watcher.isRemoved()) {
            loadedWatcher = watcher;
            // Keep the shared model in sync with the entity before adding this sample.
            watcher.writeLearningToProperties(run.learningProperties, "");
        }

        WatcherLearningEngine unloadedLearning = null;
        if (loadedWatcher == null && run.revealed) {
            unloadedLearning = new WatcherLearningEngine();
            unloadedLearning.readFromProperties(run.learningProperties, "");
        }

        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!player.isAlive() || player.isSpectator()) {
                continue;
            }

            // Learning continues at one sample per second even when the Watcher's distant
            // entity chunk is unloaded. This is important for daytime observation: it learns
            // how each participant moves, sneaks, jumps, uses items, camps, and checks behind.
            if (loadedWatcher != null) {
                loadedWatcher.observePlayerForLearning(player);
            } else if (unloadedLearning != null) {
                unloadedLearning.observePlayerBehavior(player);
            }

            UUID id = player.getUuid();
            Vec3d current = player.getPos();
            Vec3d previous = run.lastPositions.put(id, current);
            if (previous != null) {
                double moved = current.distanceTo(previous);
                double activity = Math.min(moved, 8.0D) + (player.isSprinting() ? 0.5D : 0.0D);
                run.playerExplorationScores.merge(id, activity, Double::sum);
            }
        }

        if (loadedWatcher != null) {
            loadedWatcher.writeLearningToProperties(run.learningProperties, "");
        } else if (unloadedLearning != null) {
            unloadedLearning.writeToProperties(run.learningProperties, "");
        }
    }

    private static ServerPlayerEntity choosePlayer(ServerWorld world, RunState run) {
        List<ServerPlayerEntity> players = world.getPlayers().stream()
                .filter(PlayerEntity::isAlive)
                .filter(player -> !player.isSpectator())
                .toList();
        if (players.isEmpty()) {
            return null;
        }

        // Prefer players who have explored more, but retain randomness so the timing
        // and first target are not completely predictable in multiplayer worlds.
        if (players.size() == 1 || world.random.nextFloat() < 0.65F) {
            return players.stream()
                    .max(Comparator.comparingDouble(player ->
                            run.playerExplorationScores.getOrDefault(player.getUuid(), 0.0D)))
                    .orElse(players.get(world.random.nextInt(players.size())));
        }
        return players.get(world.random.nextInt(players.size()));
    }

    private static boolean spawnFirstReveal(ServerWorld world, ServerPlayerEntity player, RunState run) {
        double bestScore = -Double.MAX_VALUE;
        int bestX = 0;
        int bestY = 0;
        int bestZ = 0;
        boolean found = false;

        int renderChunks = io.github.itsdropper.thewatcher.entity.WatcherStalkingDirector
                .renderDistanceChunks(TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS);
        double maxRevealRadius = Math.min(112.0D, Math.max(48.0D, renderChunks * 16.0D - 16.0D));
        double minRevealRadius = Math.min(64.0D, maxRevealRadius);

        for (int attempt = 0; attempt < 96; attempt++) {
            double angle = world.random.nextDouble() * Math.PI * 2.0D;
            double radius = minRevealRadius
                    + world.random.nextDouble() * (maxRevealRadius - minRevealRadius);
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * radius);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * radius);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
            BlockPos feet = new BlockPos(x, y, z);
            BlockPos ground = feet.down();
            if (!(world.getBlockState(ground).isSolidBlock(world, ground)
                    || world.getBlockState(ground).isIn(BlockTags.LEAVES))
                    || !world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                    || !world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()
                    || !world.getBlockState(feet.up(2)).getCollisionShape(world, feet.up(2)).isEmpty()
                    || !world.getBlockState(feet).getFluidState().isEmpty()
                    || !world.getBlockState(feet.up()).getFluidState().isEmpty()
                    || !world.getBlockState(feet.up(2)).getFluidState().isEmpty()) {
                continue;
            }

            int heightSum = 0;
            int samples = 0;
            int[][] offsets = {{8, 0}, {-8, 0}, {0, 8}, {0, -8}};
            for (int[] offset : offsets) {
                int sx = x + offset[0];
                int sz = z + offset[1];
                if (world.isChunkLoaded(sx >> 4, sz >> 4)) {
                    heightSum += world.getTopY(Heightmap.Type.MOTION_BLOCKING, sx, sz);
                    samples++;
                }
            }
            double prominence = samples == 0 ? 0.0D : y - heightSum / (double) samples;
            double elevation = Math.max(-16.0D, Math.min(24.0D, y - player.getY()));
            double score = elevation * 0.9D + prominence * 1.5D + world.random.nextDouble() * 5.0D;
            if (score > bestScore) {
                bestScore = score;
                bestX = x;
                bestY = y;
                bestZ = z;
                found = true;
            }
        }

        if (!found) return false;
        WatcherEntity watcher = TheWatcher.WATCHER.create(world);
        if (watcher == null) return false;
        watcher.refreshPositionAndAngles(bestX + 0.5D, bestY, bestZ + 0.5D,
                world.random.nextFloat() * 360.0F, 0.0F);
        watcher.setNaturalRunEntity(true);
        watcher.readLearningFromProperties(run.learningProperties, "");
        if (world.spawnEntity(watcher)) {
            run.naturalWatcherUuid = watcher.getUuid();
            return true;
        }
        watcher.discard();
        return false;
    }

    public static void captureLearning(ServerWorld world, WatcherEntity watcher) {
        RunState run = RUNS.get(world);
        if (run != null && run.revealed && !run.ended
                && watcher.getUuid().equals(run.naturalWatcherUuid)) {
            watcher.writeLearningToProperties(run.learningProperties, "");
        }
    }

    public static boolean shouldDiscardNaturalEntity(ServerWorld world) {
        RunState run = RUNS.get(world);
        return run != null && run.ended;
    }

    public static boolean shouldDiscardNaturalEntity(ServerWorld world, UUID entityUuid) {
        EndingState ending = ENDINGS.get(world);
        if (ending != null && ending.watcher().equals(entityUuid)) {
            return false;
        }
        RunState run = RUNS.get(world);
        if (run == null) {
            return false;
        }
        return run.ended || (run.revealed && run.naturalWatcherUuid != null
                && !run.naturalWatcherUuid.equals(entityUuid));
    }

    /**
     * Once the first reveal has happened, a missing Watcher gets ten seconds to
     * reappear naturally. If it is still gone, recreate it near a player without
     * repeating the initial join announcement.
     */
    private static void ensureWatcherPresent(ServerWorld world, RunState run) {
        if (run.naturalWatcherUuid != null) {
            net.minecraft.entity.Entity tracked = world.getEntity(run.naturalWatcherUuid);
            if (tracked instanceof WatcherEntity watcher && !watcher.isRemoved()) {
                run.missingWatcherSince = -1L;
                return;
            }
        }

        // Adopt an already-loaded natural Watcher before creating another one.
        for (ServerPlayerEntity participant : world.getPlayers()) {
            if (!participant.isAlive() || participant.isSpectator()) continue;
            List<WatcherEntity> nearby = world.getEntitiesByClass(WatcherEntity.class,
                    participant.getBoundingBox().expand(256.0D), WatcherEntity::isNaturalRunEntity);
            if (!nearby.isEmpty()) {
                WatcherEntity existing = nearby.get(0);
                run.naturalWatcherUuid = existing.getUuid();
                run.missingWatcherSince = -1L;
                return;
            }
        }

        if (run.missingWatcherSince < 0L) {
            run.missingWatcherSince = world.getTime();
            return;
        }
        if (world.getTime() - run.missingWatcherSince < 200L) {
            return;
        }

        ServerPlayerEntity target = choosePlayer(world, run);
        if (target != null && spawnFirstReveal(world, target, run)) {
            run.missingWatcherSince = -1L;
            saveRun(world, run);
            TheWatcher.LOGGER.info("Respawned the natural Watcher after it was absent for ten seconds.");
        } else {
            // Retry soon if terrain or loaded chunks prevented a safe replacement.
            run.missingWatcherSince = world.getTime() - 180L;
        }
    }

    /**
     * The Watcher can only be killed after the player has collected evidence from all four sites.
     * This ends the run as a victory instead of triggering the timed failure ending.
     */
    public static void onWatcherDefeated(ServerWorld world, ServerPlayerEntity player) {
        RunState run = RUNS.get(world);
        if (run == null || run.ended) return;

        run.ended = true;
        run.revealed = false;
        run.naturalWatcherUuid = null;
        saveRun(world, run);
        ENDINGS.remove(world);
        broadcastEndingMusic(world, false);
        for (ServerPlayerEntity participant : world.getPlayers()) {
            participant.sendMessage(Text.literal(
                    participant.getUuid().equals(player.getUuid())
                            ? "THE WATCHER IS DEAD. The evidence was real. You survived."
                            : player.getName().getString() + " destroyed The Watcher. This run is over."),
                    false);
        }
        TheWatcher.LOGGER.info("The Watcher was defeated by {} after the evidence objective was completed.",
                player.getName().getString());
    }

    private static void endRun(ServerWorld world, RunState run) {
        run.ended = true;
        saveRun(world, run);
        startEnding(world, null);
    }

    /** Keeps late joiners in sync with an ending already running in their dimension. */
    public static void syncEndingMusic(ServerPlayerEntity player) {
        if (ENDINGS.containsKey(player.getServerWorld())) {
            CameraNetwork.sendEndingMusic(player, true);
        }
    }

    /** Requests a new run from a dedicated server command. The next world tick initializes and saves it. */
    public static boolean requestStartRun(ServerWorld world) {
        if (world.getRegistryKey() != World.OVERWORLD || RUNS.containsKey(world)
                || Files.exists(runFile(world))) {
            return false;
        }
        WatcherWorldOptions.WATCHER_MODE_ENABLED = true;
        return true;
    }

    /** Cancels a pending start request; an already-running world is left untouched. */
    public static void cancelPendingRun() {
        WatcherWorldOptions.WATCHER_MODE_ENABLED = false;
    }

    public static void startDebugEnding(ServerWorld world, ServerPlayerEntity target) {
        ENDINGS.remove(world);
        startEnding(world, target);
    }

    private static void startEnding(ServerWorld world, ServerPlayerEntity requestedTarget) {
        RunState run = RUNS.get(world);
        ServerPlayerEntity target = requestedTarget;
        if (target == null && run != null) target = choosePlayer(world, run);
        if (target == null || !target.isAlive()) return;

        WatcherEntity watcher = null;
        for (ServerPlayerEntity participant : world.getPlayers()) {
            List<WatcherEntity> found = world.getEntitiesByClass(WatcherEntity.class,
                    participant.getBoundingBox().expand(2048.0D), WatcherEntity::isNaturalRunEntity);
            if (!found.isEmpty()) { watcher = found.get(0); break; }
        }
        if (watcher == null) {
            watcher = TheWatcher.WATCHER.create(world);
            if (watcher == null) return;
            double angle = Math.toRadians(target.getYaw() + 180.0F);
            int x = (int) Math.floor(target.getX() + Math.sin(angle) * 28.0D);
            int z = (int) Math.floor(target.getZ() - Math.cos(angle) * 28.0D);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                x = (int) target.getX() + 8;
                z = (int) target.getZ() + 8;
            }
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
            watcher.refreshPositionAndAngles(x + 0.5D, y, z + 0.5D, target.getYaw(), 0.0F);
            watcher.setNaturalRunEntity(true);
            world.spawnEntity(watcher);
        }
        watcher.setInvulnerable(true);
        ENDINGS.put(world, new EndingState(target.getUuid(), watcher.getUuid(), world.getTime()));
        broadcastEndingMusic(world, true);
        for (ServerPlayerEntity player : world.getPlayers()) {
            player.sendMessage(Text.literal("You hear something far beyond the trees. It has already found you."), false);
        }
    }

    private static void tickEnding(ServerWorld world, EndingState ending) {
        ServerPlayerEntity target = world.getServer().getPlayerManager().getPlayer(ending.target());
        net.minecraft.entity.Entity entity = world.getEntity(ending.watcher());
        if (target == null || !target.isAlive()) {
            broadcastEndingMusic(world, false);
            ENDINGS.remove(world);
            return;
        }
        if (!(entity instanceof WatcherEntity watcher) || watcher.isRemoved()) {
            broadcastEndingMusic(world, false);
            ENDINGS.remove(world);
            return;
        }

        long elapsed = Math.max(0L, world.getTime() - ending.startedAt());
        int remaining = Math.max(0, 1000 - (int) (elapsed / 20L) * 10);
        double actualDistance = target.distanceTo(watcher);
        if (world.getTime() % 20L == 0L) {
            target.sendMessage(Text.literal("THE WATCHER IS " + remaining + " BLOCKS AWAY"), true);
            if (remaining == 900 || remaining == 800 || remaining == 700 || remaining == 600
                    || remaining == 500 || remaining == 400 || remaining == 300 || remaining == 200) {
                String warning = switch (remaining) {
                    case 900 -> "You hear footsteps where there should be none.";
                    case 800 -> "The forest goes silent. Even the wind stops.";
                    case 700 -> "Something moved behind you. Do not turn around.";
                    case 600 -> "Your footsteps have a second set following them.";
                    case 500 -> "It is closer than the counter says.";
                    case 400 -> "You hear breathing. It stops when you stop.";
                    case 300 -> "The dark between the trees is moving.";
                    default -> "RUN.";
                };
                target.sendMessage(Text.literal(warning), false);
            }
            target.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    net.minecraft.entity.effect.StatusEffects.DARKNESS, 35, 0, false, false, true));
            if (remaining <= 300 && world.random.nextFloat() < 0.28F) {
                target.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                        net.minecraft.entity.effect.StatusEffects.NAUSEA, 50, 0, false, false, true));
            }
            if (remaining <= 500 && world.random.nextFloat() < 0.35F) {
                world.playSound(null, target.getBlockPos(), net.minecraft.sound.SoundEvents.AMBIENT_CAVE.value(),
                        net.minecraft.sound.SoundCategory.HOSTILE, 0.8F,
                        0.55F + world.random.nextFloat() * 0.25F);
            }
        }

        if (remaining <= 0 || actualDistance < 2.8D) {
            target.sendMessage(Text.literal("It was never a distance away. It was waiting."), false);
            target.damage(world.getDamageSources().generic(), Float.MAX_VALUE);
            broadcastEndingMusic(world, false);
            ENDINGS.remove(world);
            watcher.discard();
            return;
        }

        if (world.getTime() % 20L == 0L) {
            double angle = world.random.nextDouble() * Math.PI * 2.0D;
            double radius = Math.min(90.0D, Math.max(10.0D, remaining * 0.085D));
            int x = (int) Math.floor(target.getX() + Math.cos(angle) * radius);
            int z = (int) Math.floor(target.getZ() + Math.sin(angle) * radius);
            if (world.isChunkLoaded(x >> 4, z >> 4)) {
                int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
                BlockPos feet = new BlockPos(x, y, z);
                if (world.getBlockState(feet.down()).isSolidBlock(world, feet.down())
                        && world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                        && world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()) {
                    watcher.teleport(x + 0.5D, y, z + 0.5D, false);
                    watcher.getLookControl().lookAt(target, 30.0F, 30.0F);
                }
            }
        }
    }

    private static void broadcastEndingMusic(ServerWorld world, boolean playing) {
        for (ServerPlayerEntity player : world.getPlayers()) {
            CameraNetwork.sendEndingMusic(player, playing);
        }
    }

    private record EndingState(UUID target, UUID watcher, long startedAt) { }

    private static Path runFile(ServerWorld world) {
        return world.getServer().getSavePath(WorldSavePath.ROOT).resolve("the-watcher-run.properties");
    }

    private static RunState loadRun(ServerWorld world) {
        Path file = runFile(world);
        if (!Files.isRegularFile(file)) {
            return null;
        }

        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
            long startedAt = Long.parseLong(properties.getProperty("startedAt"));
            int runLengthHours = Integer.parseInt(properties.getProperty("runLengthHours"));
            long revealDelayTicks = Long.parseLong(properties.getProperty("revealDelayTicks"));
            RunState run = new RunState(startedAt, runLengthHours, revealDelayTicks);
            run.nextTowerAt = Long.parseLong(properties.getProperty("nextTowerAt",
                    Long.toString(world.getTime() + 6_000L)));
            run.revealed = Boolean.parseBoolean(properties.getProperty("revealed", "false"));
            run.ended = Boolean.parseBoolean(properties.getProperty("ended", "false"));
            String watcherUuid = properties.getProperty("naturalWatcherUuid", "");
            if (!watcherUuid.isBlank()) {
                run.naturalWatcherUuid = UUID.fromString(watcherUuid);
            }

            Set<Long> regions = new HashSet<>();
            String regionList = properties.getProperty("towerRegions", "");
            if (!regionList.isBlank()) {
                for (String value : regionList.split(",")) {
                    try {
                        regions.add(Long.parseLong(value));
                    } catch (NumberFormatException ignored) {
                        // Ignore a single malformed region entry rather than losing the run.
                    }
                }
            }
            WatcherTowerGenerator.restoreBuiltRegions(regions);
            WatcherTowerGenerator.restoreGuaranteedStructuresMask(
                    Integer.parseInt(properties.getProperty("guaranteedStructures", "0")));
            Map<Integer, BlockPos> guaranteedPositions = new java.util.HashMap<>();
            String positionList = properties.getProperty("guaranteedStructurePositions", "");
            if (!positionList.isBlank()) {
                for (String entry : positionList.split(";")) {
                    try {
                        String[] pair = entry.split(":", 2);
                        if (pair.length != 2) continue;
                        String[] coordinates = pair[1].split(",");
                        if (coordinates.length == 3) {
                            guaranteedPositions.put(Integer.parseInt(pair[0]),
                                    new BlockPos(Integer.parseInt(coordinates[0]),
                                            Integer.parseInt(coordinates[1]),
                                            Integer.parseInt(coordinates[2])));
                        }
                    } catch (IllegalArgumentException ignored) {
                        // Ignore one malformed location rather than invalidating the run save.
                    }
                }
            }
            WatcherTowerGenerator.restoreGuaranteedPositions(guaranteedPositions);
            for (String key : properties.stringPropertyNames()) {
                if (key.startsWith("learning.")) {
                    run.learningProperties.setProperty(key.substring("learning.".length()),
                            properties.getProperty(key));
                }
            }
            return run;
        } catch (IOException | IllegalArgumentException exception) {
            TheWatcher.LOGGER.warn("Could not load Watcher run state from {}", file, exception);
            return null;
        }
    }

    private static void saveRun(ServerWorld world, RunState run) {
        Path file = runFile(world);
        Properties properties = new Properties();
        properties.setProperty("startedAt", Long.toString(run.startedAt));
        properties.setProperty("runLengthHours", Integer.toString(run.runLengthHours));
        properties.setProperty("revealDelayTicks", Long.toString(run.revealDelayTicks));
        properties.setProperty("nextTowerAt", Long.toString(run.nextTowerAt));
        properties.setProperty("revealed", Boolean.toString(run.revealed));
        properties.setProperty("ended", Boolean.toString(run.ended));
        properties.setProperty("naturalWatcherUuid",
                run.naturalWatcherUuid == null ? "" : run.naturalWatcherUuid.toString());
        properties.setProperty("guaranteedStructures",
                Integer.toString(WatcherTowerGenerator.snapshotGuaranteedStructuresMask()));
        properties.setProperty("guaranteedStructurePositions",
                WatcherTowerGenerator.snapshotGuaranteedPositions().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> entry.getKey() + ":" + entry.getValue().getX() + ","
                                + entry.getValue().getY() + "," + entry.getValue().getZ())
                        .collect(java.util.stream.Collectors.joining(";")));
        if (run.naturalWatcherUuid != null) {
            net.minecraft.entity.Entity tracked = world.getEntity(run.naturalWatcherUuid);
            if (tracked instanceof WatcherEntity watcher) {
                watcher.writeLearningToProperties(run.learningProperties, "");
            }
        }
        for (String key : run.learningProperties.stringPropertyNames()) {
            properties.setProperty("learning." + key, run.learningProperties.getProperty(key));
        }
        properties.setProperty("towerRegions", WatcherTowerGenerator.snapshotBuiltRegions().stream()
                .map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));

        try {
            Files.createDirectories(file.getParent());
            try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                properties.store(writer, "The Watcher run state");
            }
        } catch (IOException exception) {
            TheWatcher.LOGGER.warn("Could not save Watcher run state to {}", file, exception);
        }
    }

    private static boolean isNight(ServerWorld world) {
        long timeOfDay = Math.floorMod(world.getTimeOfDay(), 24_000L);
        return timeOfDay >= 13_000L && timeOfDay < 23_000L;
    }

    private static final class RunState {
        private final long startedAt;
        private final int runLengthHours;
        private final long revealDelayTicks;
        private final Map<UUID, Vec3d> lastPositions = new java.util.HashMap<>();
        private final Map<UUID, Double> playerExplorationScores = new java.util.HashMap<>();
        private long nextTowerAt;
        private UUID naturalWatcherUuid;
        private boolean revealed;
        private boolean ended;
        private long missingWatcherSince = -1L;
        private final Properties learningProperties = new Properties();

        private RunState(long startedAt, int runLengthHours, long revealDelayTicks) {
            this.startedAt = startedAt;
            this.runLengthHours = runLengthHours;
            this.revealDelayTicks = revealDelayTicks;
        }
    }
}
