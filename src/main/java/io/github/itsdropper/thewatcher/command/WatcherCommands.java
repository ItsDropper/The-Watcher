package io.github.itsdropper.thewatcher.command;

import com.mojang.brigadier.CommandDispatcher;
import io.github.itsdropper.thewatcher.TheWatcher;
import io.github.itsdropper.thewatcher.entity.WatcherEntity;
import io.github.itsdropper.thewatcher.entity.WatcherStalkingDirector;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.util.Comparator;
import java.util.List;

import static net.minecraft.server.command.CommandManager.literal;

public final class WatcherCommands {
    private WatcherCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerCommands(dispatcher));
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("watcher")
                .requires(source -> source.hasPermissionLevel(2))
                .then(literal("debug")
                        .executes(context -> {
                            context.getSource().sendFeedback(() -> Text.literal(
                                    "Watcher debug: spawn | status | despawn | simulate [stop] | phase <observe|stalk|escalate|retreat|auto> | darkness <on|off>"),
                                    false);
                            return 1;
                        })
                        .then(literal("spawn").executes(context -> spawnWatcher(context.getSource())))
                        .then(literal("status").executes(context -> showStatus(context.getSource())))
                        .then(literal("despawn").executes(context -> despawnNearest(context.getSource())))
                        .then(literal("simulate")
                                .executes(context -> startSimulation(context.getSource()))
                                .then(literal("stop").executes(context -> stopSimulation(context.getSource()))))
                        .then(literal("phase")
                                .then(literal("observe").executes(context ->
                                        setNearestPhase(context.getSource(), WatcherEntity.Phase.OBSERVING)))
                                .then(literal("stalk").executes(context ->
                                        setNearestPhase(context.getSource(), WatcherEntity.Phase.STALKING)))
                                .then(literal("escalate").executes(context ->
                                        setNearestPhase(context.getSource(), WatcherEntity.Phase.ESCALATING)))
                                .then(literal("retreat").executes(context ->
                                        setNearestPhase(context.getSource(), WatcherEntity.Phase.RETREATING)))
                                .then(literal("auto").executes(context -> resumeAutomatic(context.getSource()))))
                        .then(literal("darkness")
                                .then(literal("on").executes(context -> setDarkness(context.getSource(), true)))
                                .then(literal("off").executes(context -> setDarkness(context.getSource(), false))))));
    }

    private static int spawnWatcher(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        WatcherEntity watcher = TheWatcher.WATCHER.create(source.getWorld());

        if (watcher == null) {
            source.sendError(Text.literal("Could not create The Watcher."));
            return 0;
        }

        double yawRadians = Math.toRadians(player.getYaw());
        double x = player.getX() - Math.sin(yawRadians) * 5.0D;
        double z = player.getZ() + Math.cos(yawRadians) * 5.0D;
        watcher.refreshPositionAndAngles(x, player.getY(), z, player.getYaw() + 180.0F, 0.0F);
        source.getWorld().spawnEntity(watcher);
        source.sendFeedback(() -> Text.literal(
                "Spawned The Watcher 5 blocks ahead. Use /watcher debug status."), false);
        return 1;
    }

    private static int showStatus(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        WatcherEntity watcher = findNearestWatcher(player);

        if (watcher == null) {
            source.sendError(Text.literal("No Watcher found within the current stalking search range. Use /watcher debug spawn."));
            return 0;
        }

        source.sendFeedback(() -> Text.literal("The Watcher: phase=" + watcher.getPhase()
                + ", phaseTicks=" + watcher.getPhaseTicks()
                + ", debugLock=" + watcher.isDebugPhaseLocked()
                + ", simulation=" + watcher.isDebugSimulation()
                + ", health=" + String.format(java.util.Locale.ROOT, "%.1f", watcher.getHealth())
                + ", distance=" + String.format(java.util.Locale.ROOT, "%.1f", player.distanceTo(watcher))
                + ", renderDistance=" + TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS + " chunks"
                + ", stalkingBand=" + String.format(java.util.Locale.ROOT, "%.0f-%.0f blocks",
                        WatcherStalkingDirector.minimumStalkingDistance(TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS),
                        WatcherStalkingDirector.maximumStalkingDistance(TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS))
                + ", target=" + (watcher.getTarget() == null ? "none" : watcher.getTarget().getName().getString())), false);
        return 1;
    }

    private static int setNearestPhase(ServerCommandSource source, WatcherEntity.Phase phase)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        WatcherEntity watcher = findNearestWatcher(player);

        if (watcher == null) {
            source.sendError(Text.literal("No Watcher found within the current stalking search range. Use /watcher debug spawn."));
            return 0;
        }

        watcher.forceDebugPhase(phase, player);
        source.sendFeedback(() -> Text.literal(
                "Forced nearest Watcher into " + phase + ". Phase is debug-locked until /watcher debug phase auto."), false);
        return 1;
    }

    private static int resumeAutomatic(ServerCommandSource source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        WatcherEntity watcher = findNearestWatcher(player);

        if (watcher == null) {
            source.sendError(Text.literal("No Watcher found within 64 blocks. Use /watcher debug spawn."));
            return 0;
        }

        watcher.resumeAutomaticPhases();
        source.sendFeedback(() -> Text.literal(
                "Watcher debug lock removed. Automatic night phases resumed."), false);
        return 1;
    }

    private static int despawnNearest(ServerCommandSource source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        WatcherEntity watcher = findNearestWatcher(player);

        if (watcher == null) {
            source.sendError(Text.literal("No Watcher found within 64 blocks."));
            return 0;
        }

        watcher.discard();
        source.sendFeedback(() -> Text.literal("Removed the nearest Watcher."), false);
        return 1;
    }

    private static int startSimulation(ServerCommandSource source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        WatcherEntity watcher = findNearestWatcher(player);

        if (watcher == null) {
            WatcherEntity created = TheWatcher.WATCHER.create(source.getWorld());
            if (created == null) {
                source.sendError(Text.literal("Could not create The Watcher for simulation."));
                return 0;
            }

            Vec3d spawnPosition = findDistantSimulationPosition(source.getWorld(), player);
            if (spawnPosition == null) {
                source.sendError(Text.literal(
                        "Could not find a safe, loaded stalking position near your render-distance edge. Try lowering render distance or moving to open terrain."));
                return 0;
            }

            created.refreshPositionAndAngles(spawnPosition.x, spawnPosition.y, spawnPosition.z,
                    player.getYaw() + 180.0F, 0.0F);
            source.getWorld().spawnEntity(created);
            watcher = created;
        }

        watcher.startDebugSimulation(player);
        source.sendFeedback(() -> Text.literal(
                "Started a 4x time-scaled encounter using normal Watcher behavior: observe -> stalk -> escalate -> retreat. Looking directly at it still pauses its phase timer. Use /watcher debug simulate stop to end it."), false);
        return 1;
    }

    /**
     * Place a new simulation at the edge of loaded render-distance chunks and
     * outside the player's central view, so the test begins as a distant sighting.
     */
    private static Vec3d findDistantSimulationPosition(ServerWorld world, ServerPlayerEntity player) {
        int chunks = WatcherStalkingDirector.renderDistanceChunks(TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS);
        double[] angleOffsets = {58.0D, 62.0D, 66.0D, 70.0D, 74.0D,
                -58.0D, -62.0D, -66.0D, -70.0D, -74.0D};
        double[] radiusSamples = {0.25D, 0.5D, 0.75D};

        // Prefer the render-edge band, but back off in one-chunk steps if the
        // server has not loaded the client's entire render distance.
        for (double angleOffset : angleOffsets) {
            for (double radiusSample : radiusSamples) {
                double desiredRadius = WatcherStalkingDirector.candidateRadius(chunks, radiusSample);
                for (double backoff = 0.0D; backoff <= 96.0D; backoff += 16.0D) {
                    double radius = desiredRadius - backoff;
                    if (radius < 48.0D) {
                        break;
                    }

                    double angle = Math.toRadians(player.getYaw()) + Math.PI / 2.0D
                            + Math.toRadians(angleOffset);
                    int x = (int) Math.floor(player.getX() + Math.cos(angle) * radius);
                    int z = (int) Math.floor(player.getZ() + Math.sin(angle) * radius);

                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }

                    int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos feet = new BlockPos(x, y, z);
                    BlockPos ground = feet.down();
                    if (world.getBlockState(ground).isSolidBlock(world, ground)
                            && world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                            && world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()) {
                        return new Vec3d(x + 0.5D, y, z + 0.5D);
                    }
                }
            }
        }

        return null;
    }

    private static int stopSimulation(ServerCommandSource source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        double searchRange = WatcherStalkingDirector.targetRange(TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS) + 64.0D;
        List<WatcherEntity> simulations = player.getWorld().getEntitiesByClass(
                WatcherEntity.class, player.getBoundingBox().expand(searchRange), WatcherEntity::isDebugSimulation);
        WatcherEntity watcher = simulations.stream()
                .min(Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);

        if (watcher == null) {
            source.sendError(Text.literal("No simulated Watcher found within the current stalking search range."));
            return 0;
        }

        watcher.stopDebugSimulation();
        source.sendFeedback(() -> Text.literal("Stopped the simulated encounter."), false);
        return 1;
    }

    private static int setDarkness(ServerCommandSource source, boolean enabled) {
        TheWatcher.NIGHT_DARKNESS_ENABLED = enabled;
        source.sendFeedback(() -> Text.literal("Extra night darkness " + (enabled ? "enabled" : "disabled") + "."), false);
        return 1;
    }

    private static WatcherEntity findNearestWatcher(ServerPlayerEntity player) {
        double searchRange = WatcherStalkingDirector.targetRange(TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS) + 64.0D;
        List<WatcherEntity> watchers = player.getWorld().getEntitiesByClass(
                WatcherEntity.class, player.getBoundingBox().expand(searchRange), entity -> true);
        return watchers.stream().min(Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
    }
}
