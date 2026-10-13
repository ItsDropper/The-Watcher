package io.github.itsdropper.thewatcher.command;

import com.mojang.brigadier.CommandDispatcher;
import io.github.itsdropper.thewatcher.TheWatcher;
import io.github.itsdropper.thewatcher.WatcherTowerGenerator;
import io.github.itsdropper.thewatcher.WatcherRunManager;
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
import net.minecraft.world.RaycastContext;
import net.minecraft.util.hit.BlockHitResult;

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
                .executes(context -> {
                    context.getSource().sendFeedback(() -> Text.literal(
                            "The Watcher: mode on|off | debug test|ending|spawn|status|learning|structures|despawn|simulate|phase|darkness|bloodmoon"), false);
                    return 1;
                })
                .then(literal("mode")
                        .then(literal("on").executes(context -> startMode(context.getSource())))
                        .then(literal("off").executes(context -> stopMode(context.getSource()))))
                .then(literal("debug")
                        .executes(context -> {
                            context.getSource().sendFeedback(() -> Text.literal(
                                    "Watcher debug: test | spawn | status | learning | structures | despawn | simulate [stop] | ending | phase <observe|stalk|escalate|retreat|auto> | darkness <on|off> | bloodmoon"),
                                    false);
                            return 1;
                        })
                        .then(literal("test")
                                .executes(context -> {
                                    context.getSource().sendFeedback(() -> Text.literal(
                                            "Test suite: encounter | camera | structures | cleanup | attack <melee|rush|ambush|jumpscare|blackout>. " +
                                            "Also use /watcher debug phase <observe|stalk|escalate|retreat|auto> and /watcher debug status."), false);
                                    return 1;
                                })
                                .then(literal("encounter").executes(context -> startSimulation(context.getSource())))
                                .then(literal("camera").executes(context -> showCameraStatus(context.getSource())))
                                .then(literal("structures").executes(context -> showStructures(context.getSource())))
                                .then(literal("cleanup").executes(context -> despawnNearest(context.getSource())))
                                .then(literal("attack")
                                        .executes(context -> {
                                            context.getSource().sendFeedback(() -> Text.literal(
                                                    "Choose: melee | rush | ambush | jumpscare | blackout"), false);
                                            return 1;
                                        })
                                        .then(literal("melee").executes(context -> testAttack(context.getSource(), "melee")))
                                        .then(literal("rush").executes(context -> testAttack(context.getSource(), "rush")))
                                        .then(literal("ambush").executes(context -> testAttack(context.getSource(), "ambush")))
                                        .then(literal("jumpscare").executes(context -> testAttack(context.getSource(), "jumpscare")))
                                        .then(literal("blackout").executes(context -> testAttack(context.getSource(), "blackout"))))
                        .then(literal("ending").executes(context -> triggerEnding(context.getSource())))
                        .then(literal("spawn").executes(context -> spawnWatcher(context.getSource())))
                        .then(literal("status").executes(context -> showStatus(context.getSource())))
                        .then(literal("learning").executes(context -> showLearning(context.getSource())))
                        .then(literal("structures").executes(context -> showStructures(context.getSource())))
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
                        .then(literal("bloodmoon").executes(context -> startBloodMoon(context.getSource())))
                        .then(literal("darkness")
                                .then(literal("on").executes(context -> setDarkness(context.getSource(), true)))
                                .then(literal("off").executes(context -> setDarkness(context.getSource(), false)))
                        )
                        .then(literal("attack")
                                .executes(context -> {
                                    context.getSource().sendFeedback(() -> Text.literal(
                                            "Attack tests: melee | rush | ambush | jumpscare | blackout"), false);
                                    return 1;
                                })
                                .then(literal("melee").executes(context -> testAttack(context.getSource(), "melee")))
                                .then(literal("rush").executes(context -> testAttack(context.getSource(), "rush")))
                                .then(literal("ambush").executes(context -> testAttack(context.getSource(), "ambush")))
                                .then(literal("jumpscare").executes(context -> testAttack(context.getSource(), "jumpscare")))
                                .then(literal("blackout").executes(context -> testAttack(context.getSource(), "blackout")))))));
    }

    private static int startMode(ServerCommandSource source) {
        ServerWorld world = source.getWorld();
        if (world.getRegistryKey() != net.minecraft.world.World.OVERWORLD) {
            source.sendError(Text.literal("Start Watcher mode from the Overworld."));
            return 0;
        }
        if (!WatcherRunManager.requestStartRun(world)) {
            source.sendError(Text.literal("A Watcher run already exists for this world, or a saved run file is present. Existing world data was left untouched."));
            return 0;
        }
        source.sendFeedback(() -> Text.literal("Watcher mode enabled. The server will initialize the run and save its state on the next tick."), false);
        return 1;
    }

    private static int stopMode(ServerCommandSource source) {
        WatcherRunManager.cancelPendingRun();
        source.sendFeedback(() -> Text.literal("Cancelled any pending Watcher run start. This does not stop an active run."), false);
        return 1;
    }

    private static int triggerEnding(ServerCommandSource source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        io.github.itsdropper.thewatcher.WatcherRunManager.startDebugEnding((ServerWorld) source.getWorld(), player);
        source.sendFeedback(() -> Text.literal("Ending triggered. Survive the pursuit."), false);
        return 1;
    }

    private static int spawnWatcher(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        WatcherEntity watcher = TheWatcher.WATCHER.create(source.getWorld());

        if (watcher == null) {
            source.sendError(Text.literal("Could not create The Watcher."));
            return 0;
        }

        Vec3d position = findDistantSimulationPosition(source.getWorld(), player);
        if (position == null) {
            watcher.discard();
            source.sendError(Text.literal(
                    "No safe, visible ground found nearby. Move to open ground and try again."));
            return 0;
        }

        watcher.refreshPositionAndAngles(position.x, position.y, position.z,
                player.getYaw() + 180.0F, 0.0F);
        source.getWorld().spawnEntity(watcher);
        source.sendFeedback(() -> Text.literal(
                "Spawned The Watcher on safe ground in front of you. Use /watcher debug status."), false);
        return 1;
    }

    private static int showStatus(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        WatcherEntity watcher = findNearestWatcher(player);

        if (watcher == null) {
            source.sendError(Text.literal("No loaded Watcher found within 2048 blocks. Use /watcher debug spawn or /watcher debug simulate."));
            return 0;
        }

        source.sendFeedback(() -> Text.literal("The Watcher: phase=" + watcher.getPhase()
                + ", phaseTicks=" + watcher.getPhaseTicks()
                + ", debugLock=" + watcher.isDebugPhaseLocked()
                + ", simulation=" + watcher.isDebugSimulation()
                + ", health=" + String.format(java.util.Locale.ROOT, "%.1f", watcher.getHealth())
                + ", distance=" + String.format(java.util.Locale.ROOT, "%.1f", player.distanceTo(watcher))
                + ", renderDistance=" + TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS + " chunks"
                + ", learnedApproachBonus=" + String.format(java.util.Locale.ROOT, "%.1f blocks", watcher.getLearnedApproachBonus())
                + ", avgSpeed=" + String.format(java.util.Locale.ROOT, "%.3f", watcher.getAverageHorizontalSpeed())
                + ", sprintHabit=" + String.format(java.util.Locale.ROOT, "%.2f", watcher.getAverageSprintRatio())
                + ", proximityPressure=" + String.format(java.util.Locale.ROOT, "%.2f", watcher.getProximityPressure())
                + ", stalkingBand=" + String.format(java.util.Locale.ROOT, "%.0f-%.0f blocks",
                        WatcherStalkingDirector.minimumStalkingDistance(TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS),
                        WatcherStalkingDirector.maximumStalkingDistance(TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS))
                + ", target=" + (watcher.getTarget() == null ? "none" : watcher.getTarget().getName().getString())), false);
        return 1;
    }


    private static int showLearning(ServerCommandSource source) {
        String summary = WatcherRunManager.getLearningSummary((ServerWorld) source.getWorld());
        source.sendFeedback(() -> Text.literal(summary), false);
        return summary.startsWith("No active") ? 0 : 1;
    }

    private static int showStructures(ServerCommandSource source) {
        var positions = WatcherTowerGenerator.snapshotGuaranteedPositions();
        if (positions.isEmpty()) {
            source.sendError(Text.literal("No guaranteed structure locations are recorded yet. Start a Watcher run first."));
            return 0;
        }

        StringBuilder result = new StringBuilder("Guaranteed Watcher story sites:");
        appendStructurePosition(result, positions, 1, "Watchtower");
        appendStructurePosition(result, positions, 2, "Abandoned cabin");
        appendStructurePosition(result, positions, 4, "Ritual shrine");
        appendStructurePosition(result, positions, 8, "Ruined expedition camp");
        source.sendFeedback(() -> Text.literal(result.toString()), false);
        return 1;
    }

    private static void appendStructurePosition(StringBuilder result,
                                               java.util.Map<Integer, BlockPos> positions,
                                               int key, String name) {
        BlockPos pos = positions.get(key);
        if (pos != null) {
            result.append("\n").append(name).append(": ")
                    .append(pos.getX()).append(", ").append(pos.getY()).append(", ").append(pos.getZ());
        }
    }

    private static int setNearestPhase(ServerCommandSource source, WatcherEntity.Phase phase)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        WatcherEntity watcher = findNearestWatcher(player);

        if (watcher == null) {
            source.sendError(Text.literal("No loaded Watcher found within 2048 blocks. Use /watcher debug spawn."));
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
            source.sendError(Text.literal("No loaded Watcher found within 2048 blocks. Use /watcher debug spawn."));
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
            source.sendError(Text.literal("No loaded Watcher found within 2048 blocks."));
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
        boolean created = watcher == null;

        if (created) {
            watcher = TheWatcher.WATCHER.create(source.getWorld());
            if (watcher == null) {
                source.sendError(Text.literal("Could not create The Watcher for simulation."));
                return 0;
            }
        }

        // Always reposition, including when reusing an existing entity. Otherwise a
        // previous invisible/edge-spawned Watcher makes every later simulation invisible too.
        Vec3d spawnPosition = findDistantSimulationPosition(source.getWorld(), player);
        if (spawnPosition == null) {
            if (created) watcher.discard();
            source.sendError(Text.literal(
                    "Could not find a safe, visible simulation position in loaded terrain. Move to open ground and try again."));
            return 0;
        }

        watcher.refreshPositionAndAngles(spawnPosition.x, spawnPosition.y, spawnPosition.z,
                player.getYaw() + 180.0F, 0.0F);
        if (created) {
            source.getWorld().spawnEntity(watcher);
        }

        watcher.startDebugSimulation(player);
        double distance = player.getPos().distanceTo(spawnPosition);
        source.sendFeedback(() -> Text.literal(
                "Simulation started. Watcher placed in clear sight " + String.format(java.util.Locale.ROOT, "%.0f", distance)
                        + " blocks away. Look away to let the encounter advance; escalation now performs a short rush and then retreats. Use /watcher debug status or /watcher debug simulate stop."), false);
        return 1;
    }

    /**
     * Simulations start close enough to verify visually, in front of the player, on safe loaded ground.
     * Once the player looks away, the normal stalking logic relocates the Watcher to its distant band.
     * Starting at the render edge made debugging dependent on fog, terrain, and chunk tracking.
     */
    private static Vec3d findDistantSimulationPosition(ServerWorld world, ServerPlayerEntity player) {
        double radius = 12.0D;
        double[] angleOffsets = {0.0D, 8.0D, -8.0D, 16.0D, -16.0D, 24.0D, -24.0D};
        double[] radiusScales = {1.0D, 1.25D, 1.5D, 2.0D};

        for (double radiusScale : radiusScales) {
            double candidateRadius = radius * radiusScale;
            for (double angleOffset : angleOffsets) {
                double angle = Math.toRadians(player.getYaw()) + Math.PI / 2.0D
                        + Math.toRadians(angleOffset);
                int x = (int) Math.floor(player.getX() + Math.cos(angle) * candidateRadius);
                int z = (int) Math.floor(player.getZ() + Math.sin(angle) * candidateRadius);

                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }

                int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos feet = new BlockPos(x, y, z);
                BlockPos ground = feet.down();
                if (!world.getBlockState(ground).isSolidBlock(world, ground)
                        || !world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                        || !world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()) {
                    continue;
                }

                Vec3d eyes = new Vec3d(x + 0.5D, y + 1.4D, z + 0.5D);
                Vec3d toCandidate = eyes.subtract(player.getEyePos()).normalize();
                // Require the spawn point to be in the player's forward view cone,
                // not merely unobstructed somewhere behind or beside the camera.
                if (player.getRotationVec(1.0F).normalize().dotProduct(toCandidate) < 0.82D) {
                    continue;
                }

                BlockHitResult sight = world.raycast(new RaycastContext(
                        player.getEyePos(), eyes, RaycastContext.ShapeType.COLLIDER,
                        RaycastContext.FluidHandling.NONE, player));
                if (sight.getType() != BlockHitResult.Type.MISS) {
                    continue;
                }

                return new Vec3d(x + 0.5D, y, z + 0.5D);
            }
        }

        return null;
    }

    private static int stopSimulation(ServerCommandSource source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        double searchRange = 2048.0D;
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

    private static int testAttack(ServerCommandSource source, String attack)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        WatcherEntity watcher = findNearestWatcher(player);
        if (watcher == null) {
            watcher = TheWatcher.WATCHER.create(source.getWorld());
            if (watcher == null) {
                source.sendError(Text.literal("Could not create The Watcher for attack testing."));
                return 0;
            }
            Vec3d position = findDistantSimulationPosition(source.getWorld(), player);
            if (position == null) {
                watcher.discard();
                source.sendError(Text.literal("No safe spawn position found. Move to open ground and retry."));
                return 0;
            }
            watcher.refreshPositionAndAngles(position.x, position.y, position.z,
                    player.getYaw() + 180.0F, 0.0F);
            source.getWorld().spawnEntity(watcher);
        }

        if (!watcher.debugAttack(attack, player)) {
            source.sendError(Text.literal("Attack test failed: no safe position was available for " + attack + "."));
            return 0;
        }
        source.sendFeedback(() -> Text.literal("Triggered Watcher attack test: " + attack
                + ". Use /watcher debug status to inspect its state."), false);
        return 1;
    }

    private static int showCameraStatus(ServerCommandSource source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        ServerWorld world = source.getWorld();
        List<BlockPos> cameras = io.github.itsdropper.thewatcher.CameraRegistry.getCameras(world);
        if (cameras.isEmpty()) {
            source.sendError(Text.literal("No registered cameras in this dimension. Place a camera and wait one second, then retry."));
            return 0;
        }

        BlockPos nearest = cameras.stream()
                .min(Comparator.comparingDouble(player.getBlockPos()::getSquaredDistance))
                .orElseThrow();
        boolean loaded = world.isChunkLoaded(nearest.getX() >> 4, nearest.getZ() >> 4);
        boolean validBlock = loaded && world.getBlockState(nearest).isOf(TheWatcher.SURVEILLANCE_CAMERA);
        double distance = Math.sqrt(player.getBlockPos().getSquaredDistance(nearest));
        source.sendFeedback(() -> Text.literal("Camera test: registered=" + cameras.size()
                + ", nearest=" + nearest.getX() + " " + nearest.getY() + " " + nearest.getZ()
                + ", distance=" + String.format(java.util.Locale.ROOT, "%.1f", distance)
                + ", serverChunkLoaded=" + loaded + ", cameraBlockValid=" + validBlock
                + ". Open the network screen to test the live feed; the player should remain at their current position."), false);
        return 1;
    }

    private static int startBloodMoon(ServerCommandSource source) {
        ServerWorld world = (ServerWorld) source.getWorld();
        if (world.getRegistryKey() != net.minecraft.world.World.OVERWORLD) {
            source.sendError(Text.literal("Blood Moon debug can only be triggered in the Overworld."));
            return 0;
        }

        // Day eight is a scheduled Blood Moon day; move to its night so the normal
        // BloodMoonSystem activates without a separate debug-only rendering path.
        world.setTimeOfDay(8L * 24_000L + 14_000L);
        source.sendFeedback(() -> Text.literal(
                "Blood Moon started. World time was moved to day 8 at night; normal Blood Moon effects are active."),
                false);
        return 1;
    }

    private static int setDarkness(ServerCommandSource source, boolean enabled) {
        TheWatcher.NIGHT_DARKNESS_ENABLED = enabled;
        source.sendFeedback(() -> Text.literal("Extra night darkness " + (enabled ? "enabled" : "disabled") + "."), false);
        return 1;
    }

    private static WatcherEntity findNearestWatcher(ServerPlayerEntity player) {
        // Debug controls must still find an entity after a failed/overlong relocation.
        // Keep the lookup independent of the render-distance-derived stalking band.
        double searchRange = 2048.0D;
        List<WatcherEntity> watchers = player.getWorld().getEntitiesByClass(
                WatcherEntity.class, player.getBoundingBox().expand(searchRange), entity -> true);
        return watchers.stream().min(Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
    }
}
