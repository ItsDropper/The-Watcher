package io.github.itsdropper.thewatcher;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public record CameraListPayload(List<BlockPos> positions) implements CustomPayload {
    public static final Id<CameraListPayload> ID =
            new Id<>(TheWatcher.id("camera_list"));
    public static final PacketCodec<RegistryByteBuf, CameraListPayload> CODEC = PacketCodec.of(
            (RegistryByteBuf buf, CameraListPayload payload) -> {
                buf.writeVarInt(payload.positions().size());
                for (BlockPos pos : payload.positions()) buf.writeLong(pos.asLong());
            },
            (RegistryByteBuf buf) -> {
                int count = Math.max(0, Math.min(buf.readVarInt(), 10000));
                List<BlockPos> positions = new ArrayList<>(count);
                for (int i = 0; i < count; i++) positions.add(BlockPos.fromLong(buf.readLong()));
                return new CameraListPayload(List.copyOf(positions));
            });

    public CameraListPayload {
        positions = positions.stream().map(BlockPos::toImmutable).toList();
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
