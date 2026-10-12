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
            (buf, payload) -> {
                buf.writeVarInt(payload.positions().size());
                for (BlockPos pos : payload.positions()) buf.writeBlockPos(pos);
            },
            buf -> {
                int count = Math.max(0, Math.min(buf.readVarInt(), 10000));
                List<BlockPos> positions = new ArrayList<>(count);
                for (int i = 0; i < count; i++) positions.add(buf.readBlockPos().toImmutable());
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
