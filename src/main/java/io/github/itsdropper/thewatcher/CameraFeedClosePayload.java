package io.github.itsdropper.thewatcher;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

public record CameraFeedClosePayload() implements CustomPayload {
    public static final Id<CameraFeedClosePayload> ID =
            new Id<>(TheWatcher.id("camera_feed_close"));
    public static final PacketCodec<RegistryByteBuf, CameraFeedClosePayload> CODEC =
            PacketCodec.unit(new CameraFeedClosePayload());

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
