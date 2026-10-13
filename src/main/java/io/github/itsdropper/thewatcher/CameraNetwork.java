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
    private static final Map<String, Integer> CHUNK_REFERENCES = new HashMap<>();
    private static final Set<String> FORCED_BY_US = new HashSet<>();

    private CameraNetwork() {
    }

    public static void registerPayloads() {
        PayloadTypeRegistry.playC2S().register(CameraListRequestPayload.ID, CameraListRequestPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CameraListPayload.ID, CameraListPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(EndingMusicPayload.ID, EndingMusicPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CameraFeedOpenPayload.ID, CameraFeedOpenPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CameraFeedClosePayload.ID, CameraFeedClosePayload.CODEC);
    }

    public static void sendEndingMusic(ServerPlayerEntity player, boolean playing) {
        if (ServerPlayNetworking.canSend(player, EndingMusicPayload.ID)) {
            ServerPlayNetworking.send(player, new EndingMusicPayload(playing));
        }
    }

    public record EndingMusicPayload(boolean playing) implements net.minecraft.network.packet.CustomPayload {
        public static final net.minecraft.network.packet.CustomPayload.Id<EndingMusicPayload> ID =
                new net.minecraft.network.packet.CustomPayload.Id<>(TheWatcher.id("ending_music"));
        public static final net.minecraft.network.codec.PacketCodec<net.minecraft.network.RegistryByteBuf, EndingMusicPayload> CODEC =
                net.minecraft.network.codec.PacketCodec.of((payload, buf) -> buf.writeBoolean(payload.playing()),
                        buf -> new EndingMusicPayload(buf.readBoolean()));
        @Override public net.minecraft.network.packet.CustomPayload.Id<? extends net.minecraft.network.packet.CustomPayload> getId() { return ID; }
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
                context.server().execute(() -> endFeed(context.player(), true)));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                server.execute(() -> WatcherRunManager.syncEndingMusic(handler.player)));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                server.execute(() -> endFeed(handler.player, false)));
    }

    private static void beginFeed(ServerPlayerEntity player, BlockPos pos) {
        endFeed(player, true);
        ServerWorld world = player.getServerWorld();
        if (!CameraRegistry.getCameras(world).contains(pos)
                || !world.getBlockState(pos).isOf(TheWatcher.SURVEILLANCE_CAMERA)) return;

        ChunkPos center = new ChunkPos(pos);
        // Keep the real player at their actual server position. The feed uses a client-only
        // camera entity; moving the player here made everyone else see them teleport to each camera.
        FeedSession session = new FeedSession(world, player.getVelocity(), player.isInvulnerable(),
                player.hasNoGravity(), center.x, center.z);
        SESSIONS.put(player.getUuid(), session);
        player.setInvulnerable(true);
        player.setNoGravity(true);
        player.setVelocity(Vec3d.ZERO);
        player.velocityModified = true;

        // Keep the camera's server chunks alive while the feed is open. The player itself never
        // changes position or dimension.
        for (int x = center.x - 2; x <= center.x + 2; x++) {
            for (int z = center.z - 2; z <= center.z + 2; z++) {
                acquireChunk(world, x, z);
            }
        }
    }

    private static void acquireChunk(ServerWorld world, int x, int z) {
        ChunkPos chunk = new ChunkPos(x, z);
        long packed = chunk.toLong();
        String key = chunkKey(world, packed);
        int references = CHUNK_REFERENCES.getOrDefault(key, 0);
        CHUNK_REFERENCES.put(key, references + 1);
        if (references == 0 && !world.getForcedChunks().contains(packed)) {
            world.setChunkForced(x, z, true);
            FORCED_BY_US.add(key);
        }
    }

    private static void releaseChunk(ServerWorld world, int x, int z) {
        ChunkPos chunk = new ChunkPos(x, z);
        long packed = chunk.toLong();
        String key = chunkKey(world, packed);
        Integer references = CHUNK_REFERENCES.get(key);
        if (references == null) return;
        if (references > 1) {
            CHUNK_REFERENCES.put(key, references - 1);
            return;
        }

        CHUNK_REFERENCES.remove(key);
        if (FORCED_BY_US.remove(key) && world.getForcedChunks().contains(packed)) {
            world.setChunkForced(x, z, false);
        }
    }

    private static String chunkKey(ServerWorld world, long packedChunk) {
        return world.getRegistryKey().getValue() + "|" + packedChunk;
    }

    private static void endFeed(ServerPlayerEntity player, boolean restorePlayer) {
        FeedSession session = SESSIONS.remove(player.getUuid());
        if (session == null) return;

        ServerWorld cameraWorld = session.world();
        for (int x = session.cameraChunkX() - 2; x <= session.cameraChunkX() + 2; x++) {
            for (int z = session.cameraChunkZ() - 2; z <= session.cameraChunkZ() + 2; z++) {
                releaseChunk(cameraWorld, x, z);
            }
        }

        // A disconnected player must not have state mutated after logout.
        if (!restorePlayer || player.getServer() == null) return;
        player.setVelocity(session.velocity());
        player.velocityModified = true;
        player.setInvulnerable(session.wasInvulnerable());
        player.setNoGravity(session.hadNoGravity());
    }

    private record FeedSession(ServerWorld world, Vec3d velocity, boolean wasInvulnerable,
                               boolean hadNoGravity, int cameraChunkX, int cameraChunkZ) {
    }
}
