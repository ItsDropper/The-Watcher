package io.github.itsdropper.thewatcher;

import io.github.itsdropper.thewatcher.SurveillanceCameraBlock;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CameraNetwork {
    public static final Identifier REQUEST = TheWatcher.id("camera_list_request");
    public static final Identifier CAMERA_LIST = TheWatcher.id("camera_list");
    public static final Identifier OPEN_FEED = TheWatcher.id("camera_feed_open");
    public static final Identifier CLOSE_FEED = TheWatcher.id("camera_feed_close");

    private static final Map<UUID, FeedSession> SESSIONS = new HashMap<>();
    private static final Set<String> FORCED_BY_US = new HashSet<>();

    private CameraNetwork() {
    }

    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(REQUEST, (server, player, handler, buf, responseSender) ->
                server.execute(() -> {
                    var cameras = CameraRegistry.getCameras(player.getServerWorld());
                    var response = PacketByteBufs.create();
                    response.writeVarInt(cameras.size());
                    for (BlockPos pos : cameras) response.writeBlockPos(pos);
                    ServerPlayNetworking.send(player, CAMERA_LIST, response);
                }));

        ServerPlayNetworking.registerGlobalReceiver(OPEN_FEED, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos().toImmutable();
            server.execute(() -> beginFeed(player, pos));
        });
        ServerPlayNetworking.registerGlobalReceiver(CLOSE_FEED, (server, player, handler, buf, responseSender) ->
                server.execute(() -> endFeed(player)));

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
                    world.setChunkForced(chunk, true);
                    FORCED_BY_US.add(key);
                }
            }
        }

        // Moving the server-side player to the camera makes vanilla stream remote terrain
        // to the client; a forced server chunk alone would not send distant chunks to the client.
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

        ServerWorld current = player.getServerWorld();
        ChunkPos center = new ChunkPos(player.getBlockPos());
        // Release only chunks that were not force-loaded before this feed began.
        for (int x = center.x - 2; x <= center.x + 2; x++) {
            for (int z = center.z - 2; z <= center.z + 2; z++) {
                ChunkPos chunk = new ChunkPos(x, z);
                String key = current.getRegistryKey().getValue() + "|" + chunk.toLong();
                if (FORCED_BY_US.remove(key)) current.setChunkForced(chunk, false);
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
