package io.github.itsdropper.thewatcher;

import io.github.itsdropper.thewatcher.entity.WatcherEntity;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Controls a single randomized run. During the learning stage there is deliberately
 * no Watcher entity in the world, so it cannot accidentally be spotted early.
 */
public final class WatcherRunManager {
    private static final Map<ServerWorld, RunState> RUNS = new WeakHashMap<>();
    private static final long TICKS_PER_HOUR = 72_000L;

    private WatcherRunManager() {
    }

    public static void tick(ServerWorld world) {
        if (world.getRegistryKey() != World.OVERWORLD) {
            return;
        }

        RunState run = RUNS.get(world);
        if (run == null) {
            // The world-creation toggle applies to the next Overworld that starts ticking.
            if (!WatcherWorldOptions.WATCHER_MODE_ENABLED) {
                return;
            }
            run = new RunState(world.getTime(), WatcherWorldOptions.getRunLengthHours(),
                    world.random.nextInt(6_001) + 3_600L);
            run.nextTowerAt = world.getTime() + world.random.nextInt(4_801) + 2_400L;
            WatcherTowerGenerator.resetForRun();
            RUNS.put(world, run);
            WatcherWorldOptions.WATCHER_MODE_ENABLED = false;
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

        if (world.getTime() >= run.nextTowerAt) {
            ServerPlayerEntity explorer = choosePlayer(world, run);
            if (explorer != null && WatcherTowerGenerator.tryGenerate(world, explorer)) {
                run.nextTowerAt = world.getTime() + world.random.nextInt(18_001) + 18_000L;
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
                run.explorationScore += activity;
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
                    || !world.getBlockState(feet).getFluidState().isEmpty()
                    || !world.getBlockState(feet.up()).getFluidState().isEmpty()) {
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
        private double explorationScore;
        private boolean revealed;
        private boolean ended;

        private RunState(long startedAt, int runLengthHours, long revealDelayTicks) {
            this.startedAt = startedAt;
            this.runLengthHours = runLengthHours;
            this.revealDelayTicks = revealDelayTicks;
        }
    }
}
