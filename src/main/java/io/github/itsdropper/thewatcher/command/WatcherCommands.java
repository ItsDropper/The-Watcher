package io.github.itsdropper.thewatcher.command;

import com.mojang.brigadier.CommandDispatcher;
import io.github.itsdropper.thewatcher.TheWatcher;
import io.github.itsdropper.thewatcher.entity.WatcherEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

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
                                    "Watcher debug: spawn | status | phase <observe|stalk|escalate|retreat> | darkness <on|off>"),
                                    false);
                            return 1;
                        })
                        .then(literal("spawn").executes(context -> spawnWatcher(context.getSource())))
                        .then(literal("status").executes(context -> showStatus(context.getSource())))
                        .then(literal("phase")
                                .then(literal("observe").executes(context ->
                                        setNearestPhase(context.getSource(), WatcherEntity.Phase.OBSERVING)))
                                .then(literal("stalk").executes(context ->
                                        setNearestPhase(context.getSource(), WatcherEntity.Phase.STALKING)))
                                .then(literal("escalate").executes(context ->
                                        setNearestPhase(context.getSource(), WatcherEntity.Phase.ESCALATING)))
                                .then(literal("retreat").executes(context ->
                                        setNearestPhase(context.getSource(), WatcherEntity.Phase.RETREATING))))
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
        source.sendFeedback(() -> Text.literal("Spawned The Watcher 5 blocks ahead. Use /watcher debug status."), false);
        return 1;
    }

    private static int showStatus(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        WatcherEntity watcher = findNearestWatcher(player);

        if (watcher == null) {
            source.sendError(Text.literal("No Watcher found within 64 blocks. Use /watcher debug spawn."));
            return 0;
        }

        source.sendFeedback(() -> Text.literal("The Watcher: phase=" + watcher.getPhase()
                + ", phaseTicks=" + watcher.getPhaseTicks()
                + ", health=" + String.format(java.util.Locale.ROOT, "%.1f", watcher.getHealth())
                + ", target=" + (watcher.getTarget() == null ? "none" : watcher.getTarget().getName().getString())), false);
        return 1;
    }

    private static int setNearestPhase(ServerCommandSource source, WatcherEntity.Phase phase)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        WatcherEntity watcher = findNearestWatcher(player);

        if (watcher == null) {
            source.sendError(Text.literal("No Watcher found within 64 blocks. Use /watcher debug spawn."));
            return 0;
        }

        watcher.setPhase(phase);
        source.sendFeedback(() -> Text.literal("Forced nearest Watcher into phase: " + phase), false);
        return 1;
    }

    private static int setDarkness(ServerCommandSource source, boolean enabled) {
        TheWatcher.NIGHT_DARKNESS_ENABLED = enabled;
        source.sendFeedback(() -> Text.literal("Extra night darkness " + (enabled ? "enabled" : "disabled") + "."), false);
        return 1;
    }

    private static WatcherEntity findNearestWatcher(ServerPlayerEntity player) {
        List<WatcherEntity> watchers = player.getWorld().getEntitiesByClass(
                WatcherEntity.class, player.getBoundingBox().expand(64.0D), entity -> true);
        return watchers.stream().min(Comparator.comparingDouble(player::squaredDistanceTo)).orElse(null);
    }
}
