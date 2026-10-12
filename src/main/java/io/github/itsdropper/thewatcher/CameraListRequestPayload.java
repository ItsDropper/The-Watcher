package io.github.itsdropper.thewatcher;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record CameraListRequestPayload() implements CustomPayload {
    public static final Id<CameraListRequestPayload> ID =
            new Id<>(TheWatcher.id("camera_list_request"));
    public static final PacketCodec<RegistryByteBuf, CameraListRequestPayload> CODEC =
            PacketCodec.unit(new CameraListRequestPayload());

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
