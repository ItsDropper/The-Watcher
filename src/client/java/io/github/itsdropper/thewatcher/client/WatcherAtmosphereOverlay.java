package io.github.itsdropper.thewatcher.client;

import io.github.itsdropper.thewatcher.BloodMoonSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.world.World;

/**
 * A restrained peripheral vignette for The Watcher's oppressive atmosphere.
 *
 * It is drawn as nested translucent edge bands rather than a full-screen tint:
 * the centre remains readable while the edges lose detail, creating the sense
 * that the player cannot quite trust what is at the edge of their vision.
 * No textures, framebuffers, particles, or per-frame allocations are required.
 */
public final class WatcherAtmosphereOverlay {
    private static final int BANDS = 18;
    private static final int EDGE_COLOR = 0x0005080A;

    private WatcherAtmosphereOverlay() {}

    public static void render(DrawContext context) {
        if (!WatcherFogConfig.shouldApply()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null
                || client.world.getRegistryKey() != World.OVERWORLD) return;

        float strength = switch (WatcherFogConfig.getDetail()) {
            case OFF -> 0.0F;
            case LOW -> 0.22F;
            case MEDIUM -> 0.38F;
            case HIGH -> 0.56F;
            case ULTRA -> 0.76F;
        };
        if (strength <= 0.0F) return;

        long time = Math.floorMod(client.world.getTimeOfDay(), 24_000L);
        float night = time >= 13_000L && time < 23_000L ? 1.0F : 0.0F;
        float rain = client.world.isRaining() ? 1.0F : 0.0F;
        float bloodMoon = BloodMoonSystem.isActive(client.world) ? 1.0F : 0.0F;
        strength = Math.min(0.94F, strength + night * 0.12F + rain * 0.10F + bloodMoon * 0.18F);

        int width = context.getScaledWindowWidth();
        int height = context.getScaledWindowHeight();
        if (width < 80 || height < 60) return;

        int maxX = Math.max(8, width / 7);
        int maxY = Math.max(8, height / 7);
        for (int band = 0; band < BANDS; band++) {
            float progress = (band + 1.0F) / BANDS;
            int insetX = Math.round(maxX * (1.0F - progress));
            int insetY = Math.round(maxY * (1.0F - progress));
            int alpha = Math.min(110, Math.round(2.0F + 30.0F * strength * progress * progress));
            int color = (alpha << 24) | EDGE_COLOR;

            // Nested bands build a soft, stepped vignette with no centre-screen wash.
            context.fill(0, 0, Math.max(1, insetX), height, color);
            context.fill(width - Math.max(1, insetX), 0, width, height, color);
            context.fill(0, 0, width, Math.max(1, insetY), color);
            context.fill(0, height - Math.max(1, insetY), width, height, color);
        }
    }
}
