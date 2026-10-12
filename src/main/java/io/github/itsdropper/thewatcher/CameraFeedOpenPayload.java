package io.github.itsdropper.thewatcher;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;

public record CameraFeedOpenPayload(BlockPos pos) implements CustomPayload {
    public static final Id<CameraFeedOpenPayload> ID =
            new Id<>(TheWatcher.id("camera_feed_open"));
    public static final PacketCodec<RegistryByteBuf, CameraFeedOpenPayload> CODEC = PacketCodec.of(
            (buf, payload) -> buf.writeBlockPos(payload.pos()),
            buf -> new CameraFeedOpenPayload(buf.readBlockPos().toImmutable()));

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
