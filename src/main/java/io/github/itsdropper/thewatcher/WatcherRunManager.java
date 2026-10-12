package io.github.itsdropper.thewatcher;

import io.github.itsdropper.thewatcher.entity.WatcherEntity;
import net.minecraft.block.BlockState;
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

    private WatcherRunManager() {
    }

    public static void tick(ServerWorld world) {
        if (world.getRegistryKey() != World.OVERWORLD) {
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
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!player.isAlive() || player.isSpectator()) {
                continue;
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
        for (int attempt = 0; attempt < 48; attempt++) {
            double angle = world.random.nextDouble() * Math.PI * 2.0D;
            double radius = 42.0D + world.random.nextDouble() * 34.0D;
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * radius);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * radius);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }

            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos feet = new BlockPos(x, y, z);
            BlockPos ground = feet.down();
            BlockState groundState = world.getBlockState(ground);
            if (!groundState.isSolidBlock(world, ground)
                    || !world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                    || !world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()
                    || !world.getBlockState(feet.up(2)).getCollisionShape(world, feet.up(2)).isEmpty()
                    || !world.getBlockState(feet).getFluidState().isEmpty()
                    || !world.getBlockState(feet.up()).getFluidState().isEmpty()
                    || !world.getBlockState(feet.up(2)).getFluidState().isEmpty()) {
                continue;
            }

            WatcherEntity watcher = TheWatcher.WATCHER.create(world);
            if (watcher == null) {
                return false;
            }
            watcher.refreshPositionAndAngles(x + 0.5D, y, z + 0.5D,
                    world.random.nextFloat() * 360.0F, 0.0F);
            watcher.setNaturalRunEntity(true);
            if (world.spawnEntity(watcher)) {
                run.naturalWatcherUuid = watcher.getUuid();
                return true;
            }
            watcher.discard();
        }
        return false;
    }

    public static boolean shouldDiscardNaturalEntity(ServerWorld world) {
        RunState run = RUNS.get(world);
        return run != null && run.ended;
    }

    private static void endRun(ServerWorld world, RunState run) {
        run.ended = true;
        saveRun(world, run);
        if (run.naturalWatcherUuid != null) {
            net.minecraft.entity.Entity entity = world.getEntity(run.naturalWatcherUuid);
            if (entity != null) {
                entity.discard();
            }
        }
        for (ServerPlayerEntity player : world.getPlayers()) {
            player.sendMessage(Text.literal("The Watcher has gone quiet. This run is over."), false);
        }
    }

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

        private RunState(long startedAt, int runLengthHours, long revealDelayTicks) {
            this.startedAt = startedAt;
            this.runLengthHours = runLengthHours;
            this.revealDelayTicks = revealDelayTicks;
        }
    }
}
