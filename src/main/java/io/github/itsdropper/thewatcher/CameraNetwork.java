package io.github.itsdropper.thewatcher;

import io.github.itsdropper.thewatcher.SurveillanceCameraBlock;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CameraNetwork {
    private static final Map<UUID, FeedSession> SESSIONS = new HashMap<>();
    private static final Set<String> FORCED_BY_US = new HashSet<>();

    private CameraNetwork() {
    }

    public static void registerPayloads() {
        PayloadTypeRegistry.playC2S().register(CameraListRequestPayload.ID, CameraListRequestPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CameraListPayload.ID, CameraListPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CameraFeedOpenPayload.ID, CameraFeedOpenPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CameraFeedClosePayload.ID, CameraFeedClosePayload.CODEC);
    }

    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(CameraListRequestPayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    var cameras = CameraRegistry.getCameras(context.player().getServerWorld());
                    ServerPlayNetworking.send(context.player(), new CameraListPayload(cameras));
                }));

        ServerPlayNetworking.registerGlobalReceiver(CameraFeedOpenPayload.ID, (payload, context) ->
                context.server().execute(() -> beginFeed(context.player(), payload.pos())));
        ServerPlayNetworking.registerGlobalReceiver(CameraFeedClosePayload.ID, (payload, context) ->
                context.server().execute(() -> endFeed(context.player())));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                server.execute(() -> endFeed(handler.player)));
    }

    private static void beginFeed(ServerPlayerEntity player, BlockPos pos) {
        endFeed(player);
        ServerWorld world = player.getServerWorld();
        if (!CameraRegistry.getCameras(world).contains(pos)
                || !world.getBlockState(pos).isOf(TheWatcher.SURVEILLANCE_CAMERA)) return;

        FeedSession session = new FeedSession(world, player.getX(), player.getY(), player.getZ(),
                player.getYaw(), player.getPitch(), player.getVelocity(), player.isInvulnerable());
        SESSIONS.put(player.getUuid(), session);
        player.setInvulnerable(true);

        ChunkPos center = new ChunkPos(pos);
        for (int x = center.x - 2; x <= center.x + 2; x++) {
            for (int z = center.z - 2; z <= center.z + 2; z++) {
                ChunkPos chunk = new ChunkPos(x, z);
                String key = world.getRegistryKey().getValue() + "|" + chunk.toLong();
                if (!world.getForcedChunks().contains(chunk.toLong())) {
                    world.setChunkForced(x, z, true);
                    FORCED_BY_US.add(key);
                }
            }
        }

        // Vanilla streams terrain around the server-side player, not around client-only camera entities.
        float facingYaw = world.getBlockState(pos).contains(SurveillanceCameraBlock.FACING)
                ? world.getBlockState(pos).get(SurveillanceCameraBlock.FACING).asRotation() : 180.0F;
        player.teleport(world, pos.getX() + 0.5D, pos.getY() - 0.7D, pos.getZ() + 0.5D,
                facingYaw, 0.0F);
        player.setVelocity(Vec3d.ZERO);
        player.velocityModified = true;
    }

    private static void endFeed(ServerPlayerEntity player) {
        FeedSession session = SESSIONS.remove(player.getUuid());
        if (session == null) return;

        ChunkPos center = new ChunkPos(player.getBlockPos());
        for (int x = center.x - 2; x <= center.x + 2; x++) {
            for (int z = center.z - 2; z <= center.z + 2; z++) {
                ChunkPos chunk = new ChunkPos(x, z);
                String key = player.getServerWorld().getRegistryKey().getValue() + "|" + chunk.toLong();
                if (FORCED_BY_US.remove(key)) player.getServerWorld().setChunkForced(x, z, false);
            }
        }

        player.teleport(session.world(), session.x(), session.y(), session.z(), session.yaw(), session.pitch());
        player.setVelocity(session.velocity());
        player.velocityModified = true;
        player.setInvulnerable(session.wasInvulnerable());
    }

    private record FeedSession(ServerWorld world, double x, double y, double z, float yaw, float pitch,
                               Vec3d velocity, boolean wasInvulnerable) {
    }
}
