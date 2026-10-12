package io.github.itsdropper.thewatcher.client;

import io.github.itsdropper.thewatcher.TheWatcher;
import io.github.itsdropper.thewatcher.client.render.WatcherRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class TheWatcherClient implements ClientModInitializer {
    private static KeyBinding openCameraMonitorKey;

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(TheWatcher.WATCHER, WatcherRenderer::new);
        CameraAlertHud.register();
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
