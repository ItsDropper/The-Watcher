package io.github.itsdropper.thewatcher.client;

import io.github.itsdropper.thewatcher.CameraNetwork;
import io.github.itsdropper.thewatcher.TheWatcher;
import io.github.itsdropper.thewatcher.client.render.WatcherRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;

public class TheWatcherClient implements ClientModInitializer {
    private static KeyBinding openCameraMonitorKey;

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(TheWatcher.WATCHER, WatcherRenderer::new);
        CameraAlertHud.register();
        ClientPlayNetworking.registerGlobalReceiver(CameraNetwork.CAMERA_LIST, (client, handler, buf, responseSender) -> {
            int count = Math.min(buf.readVarInt(), 10000);
            var cameras = new ArrayList<net.minecraft.util.math.BlockPos>(count);
            for (int i = 0; i < count; i++) {
                cameras.add(buf.readBlockPos().toImmutable());
            }
            client.execute(() -> CameraMonitorScreen.receiveCameras(cameras));
        });

        openCameraMonitorKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.the-watcher.camera_monitor", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V,
                "category.the-watcher"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.options != null) {
                TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS =
                        Math.max(4, client.options.getViewDistance().getValue());
            }
            while (openCameraMonitorKey.wasPressed()) {
                if (client.player != null && client.world != null && client.currentScreen == null) {
                    client.setScreen(new CameraMonitorScreen());
                }
            }
        });
    }
}
