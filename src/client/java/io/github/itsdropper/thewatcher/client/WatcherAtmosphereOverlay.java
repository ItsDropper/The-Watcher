package io.github.itsdropper.thewatcher.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.world.World;

/**
 * Applies a subtle neutral-gray grade to the sky portion of the view.
 * Fog itself is handled by the world renderer; no screen-wide haze is drawn.
 */
public final class WatcherAtmosphereOverlay {
    private WatcherAtmosphereOverlay() {}

    public static void render(DrawContext context) {
        if (!WatcherFogConfig.shouldApply()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null
                || client.world.getRegistryKey() != World.OVERWORLD) return;

        int width = context.getScaledWindowWidth();
        int height = context.getScaledWindowHeight();
        if (width < 80 || height < 60) return;

        // A restrained neutral-gray tint over the upper sky only; no full-screen overlay.
        int skyHeight = Math.round(height * 0.48F);
        int alpha = switch (WatcherFogConfig.getDetail()) {
            case OFF -> 0;
            case LOW -> 8;
            case MEDIUM -> 13;
            case HIGH -> 19;
            case ULTRA -> 25;
        };
        if (alpha > 0) {
            context.fill(0, 0, width, skyHeight, (alpha << 24) | 0x777B7D);
        }
    }
}
