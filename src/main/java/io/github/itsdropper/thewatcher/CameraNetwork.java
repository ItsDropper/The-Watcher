package io.github.itsdropper.thewatcher;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public final class CameraNetwork {
    public static final Identifier REQUEST = TheWatcher.id("camera_list_request");
    public static final Identifier CAMERA_LIST = TheWatcher.id("camera_list");

    private CameraNetwork() {
    }

    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(REQUEST, (server, player, handler, buf, responseSender) ->
                server.execute(() -> {
                    var cameras = CameraRegistry.getCameras(player.getServerWorld());
                    var response = PacketByteBufs.create();
                    response.writeVarInt(cameras.size());
                    for (BlockPos pos : cameras) {
                        response.writeBlockPos(pos);
                    }
                    ServerPlayNetworking.send(player, CAMERA_LIST, response);
                }));
    }
}
