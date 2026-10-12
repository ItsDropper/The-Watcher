package io.github.itsdropper.thewatcher;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;

public record CameraFeedOpenPayload(BlockPos pos) implements CustomPayload {
    public static final Id<CameraFeedOpenPayload> ID =
            new Id<>(TheWatcher.id("camera_feed_open"));
    public static final PacketCodec<RegistryByteBuf, CameraFeedOpenPayload> CODEC = PacketCodec.of(
            (RegistryByteBuf buf, CameraFeedOpenPayload payload) -> buf.writeLong(payload.pos().asLong()),
            (RegistryByteBuf buf) -> new CameraFeedOpenPayload(BlockPos.fromLong(buf.readLong())));

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
