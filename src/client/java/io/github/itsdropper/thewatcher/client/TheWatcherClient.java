package io.github.itsdropper.thewatcher.client;

import io.github.itsdropper.thewatcher.CameraNetwork;
import io.github.itsdropper.thewatcher.CameraListPayload;
import io.github.itsdropper.thewatcher.CameraListRequestPayload;
import io.github.itsdropper.thewatcher.TheWatcher;
import io.github.itsdropper.thewatcher.client.render.WatcherRenderer;
import io.github.itsdropper.thewatcher.client.hosting.HostingTunnelService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.text.Text;
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
    private static int desktopAnomalyDelay = -1;
    private static Object desktopAnomalyWorld;

    public static boolean isEndingMusicActive() {
        return endingMusic != null;
    }

    private static boolean clientIsInWorld() {
        var client = net.minecraft.client.MinecraftClient.getInstance();
        return client.player != null && client.world != null;
    }

    @Override
    public void onInitializeClient() {
        DesktopAnomalyConfig.load();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("watcherclient")
                        .then(ClientCommandManager.literal("debug")
                                .then(ClientCommandManager.literal("desktop-popup")
                                        .executes(context -> {
                                            if (clientIsInWorld()) {
                                                DesktopAnomalyLauncher.showRandomPopup(
                                                        net.minecraft.util.math.random.Random.create());
                                                context.getSource().sendFeedback(Text.literal(
                                                        "Requested a native The Watcher desktop popup. Check Windows if it does not appear."));
                                            } else {
                                                context.getSource().sendError(Text.literal(
                                                        "Join a world before testing the desktop popup."));
                                            }
                                            return 1;
                                        })))));
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

            // Desktop effects are strictly opt-in and never run outside the Overworld.
            if (client.player == null || client.world == null) {
                desktopAnomalyDelay = -1;
                desktopAnomalyWorld = null;
                return;
            }
            if (desktopAnomalyWorld != client.world) {
                desktopAnomalyWorld = client.world;
                desktopAnomalyDelay = 6_000 + client.world.random.nextInt(12_001);
            }
            if (!DesktopAnomalyConfig.ENABLED) {
                desktopAnomalyDelay = -1;
                return;
            }
            if (desktopAnomalyDelay < 0) {
                desktopAnomalyDelay = 6_000 + client.world.random.nextInt(12_001);
            } else if (desktopAnomalyDelay > 0) {
                desktopAnomalyDelay--;
            } else if (client.currentScreen == null
                    && client.world.getRegistryKey() == World.OVERWORLD) {
                // Rare desktop interruption: 35% chance every 20–40 minutes after the first delay.
                if (client.world.random.nextInt(100) < 35) {
                    DesktopAnomalyLauncher.showRandomPopup(client.world.random);
                }
                desktopAnomalyDelay = 24_000 + client.world.random.nextInt(24_001);
            }
        });
    }
}
