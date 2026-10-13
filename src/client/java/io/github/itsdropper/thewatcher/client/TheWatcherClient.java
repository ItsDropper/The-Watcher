package io.github.itsdropper.thewatcher.client;

import io.github.itsdropper.thewatcher.CameraNetwork;
import io.github.itsdropper.thewatcher.CameraListPayload;
import io.github.itsdropper.thewatcher.CameraListRequestPayload;
import io.github.itsdropper.thewatcher.TheWatcher;
import io.github.itsdropper.thewatcher.client.render.WatcherRenderer;
import io.github.itsdropper.thewatcher.client.hosting.HostingTunnelService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.world.World;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;


public class TheWatcherClient implements ClientModInitializer {
    private static KeyBinding openCameraMonitorKey;
    private static PositionedSoundInstance endingMusic;
    private static int windowsAnomalyDelay = -1;
    private static Object windowsAnomalyWorld;

    public static boolean isEndingMusicActive() {
        return endingMusic != null;
    }

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(TheWatcher.WATCHER, WatcherRenderer::new);
        CameraAlertHud.register();
        ClientPlayNetworking.registerGlobalReceiver(io.github.itsdropper.thewatcher.CameraNetwork.EndingMusicPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    if (payload.playing()) {
                        if (endingMusic != null && context.client().getSoundManager().isPlaying(endingMusic)) return;
                        endingMusic = new PositionedSoundInstance(
                                TheWatcher.id("ending_theme"),
                                SoundCategory.MUSIC,
                                1.0F,
                                1.0F,
                                SoundInstance.createRandom(),
                                true,
                                0,
                                SoundInstance.AttenuationType.NONE,
                                0.0D, 0.0D, 0.0D,
                                true);
                        context.client().getSoundManager().play(endingMusic);
                    } else {
                        if (endingMusic != null) {
                            context.client().getSoundManager().stop(endingMusic);
                            endingMusic = null;
                        }
                    }
                }));
        ClientPlayNetworking.registerGlobalReceiver(CameraListPayload.ID, (payload, context) ->
                context.client().execute(() -> CameraMonitorScreen.receiveCameras(payload.positions())));

        // Never leave the ending soundtrack running after leaving a world or server.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                client.execute(() -> {
                    if (endingMusic != null) {
                        client.getSoundManager().stop(endingMusic);
                        endingMusic = null;
                    }
                    HostingTunnelService.closeActiveSession();
                }));

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

            // Extremely rare fictional OS-style dialogs, rendered inside Minecraft only.
            // Reset the initial delay for each world; never interrupt menus or other screens.
            if (client.player == null || client.world == null) {
                windowsAnomalyDelay = -1;
                windowsAnomalyWorld = null;
                return;
            }
            if (windowsAnomalyWorld != client.world) {
                windowsAnomalyWorld = client.world;
                windowsAnomalyDelay = 6_000 + client.world.random.nextInt(12_001);
            }
            if (windowsAnomalyDelay > 0) {
                windowsAnomalyDelay--;
            } else if (client.currentScreen == null
                    && client.world.getRegistryKey() == World.OVERWORLD) {
                // A single 35% roll every 20–40 minutes keeps it rare rather than predictable.
                if (client.world.random.nextInt(100) < 35) {
                    String[][] anomalies = {
                            {"Windows Security", "An unfamiliar process accessed this window.",
                                    "The process could not be identified."},
                            {"Application Error", "The requested object could not be located.",
                                    "It may have been moved or may no longer exist."},
                            {"Windows", "This application is waiting for a response.",
                                    "No response was received from the requested window."},
                            {"System Notification", "A background task changed state.",
                                    "The previous state could not be restored."},
                            {"Windows Security", "An unexpected window was detected.",
                                    "The source of this window is unknown."}
                    };
                    String[] anomaly = anomalies[client.world.random.nextInt(anomalies.length)];
                    client.setScreen(new WindowsAnomalyScreen(client.currentScreen,
                            anomaly[0], anomaly[1], anomaly[2]));
                }
                windowsAnomalyDelay = 24_000 + client.world.random.nextInt(24_001);
            }
        });
    }
}
