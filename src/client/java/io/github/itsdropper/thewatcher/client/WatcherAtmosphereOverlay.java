package io.github.itsdropper.thewatcher.client;

import io.github.itsdropper.thewatcher.BloodMoonSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.world.World;

/** Screen-space near fog and a darkened overcast sky grade, rendered before the HUD. */
public final class WatcherAtmosphereOverlay {
    private static final int BANDS = 20;
    private WatcherAtmosphereOverlay() {}

    public static void render(DrawContext context) {
        if (!WatcherFogConfig.shouldApply()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null
                || client.world.getRegistryKey() != World.OVERWORLD) return;

        float strength = switch (WatcherFogConfig.getDetail()) {
            case OFF -> 0.0F;
            case LOW -> 0.34F;
            case MEDIUM -> 0.55F;
            case HIGH -> 0.76F;
            case ULTRA -> 0.94F;
        };
        if (strength <= 0.0F) return;
        long time = Math.floorMod(client.world.getTimeOfDay(), 24_000L);
        float night = time >= 13_000L && time < 23_000L ? 1.0F : 0.0F;
        float rain = client.world.isRaining() ? 1.0F : 0.0F;
        float bloodMoon = BloodMoonSystem.isActive(client.world) ? 1.0F : 0.0F;
        strength = Math.min(1.0F, strength + night * 0.10F + rain * 0.08F + bloodMoon * 0.16F);

        int width = context.getScaledWindowWidth();
        int height = context.getScaledWindowHeight();
        if (width < 80 || height < 60) return;

        // Low-opacity veil means even nearby terrain looks swallowed by haze.
        int veilAlpha = Math.round(10.0F + 31.0F * strength);
        context.fill(0, 0, width, height, (veilAlpha << 24) | 0x18232A);

        // Persistent slate-dark sky tint: deliberately overcast even at midday.
        int skyHeight = Math.round(height * 0.62F);
        for (int band = 0; band < BANDS; band++) {
            float progress = (band + 1.0F) / BANDS;
            int y = Math.round(skyHeight * band / (float) BANDS);
            int nextY = Math.max(y + 1, Math.round(skyHeight * (band + 1) / (float) BANDS));
            int alpha = Math.round((7.0F + 23.0F * strength) * (1.0F - progress * 0.38F));
            context.fill(0, y, width, nextY, (alpha << 24) | 0x101B22);
        }

        // Several translucent layers create visible close fog around the horizon/ground.
        int horizonY = Math.round(height * 0.43F);
        for (int band = 0; band < BANDS; band++) {
            float progress = (band + 1.0F) / BANDS;
            int thickness = Math.max(2, Math.round(height * 0.19F / BANDS));
            int y = horizonY + Math.round(height * 0.22F * band / BANDS);
            int alpha = Math.round(3.0F + 25.0F * strength * (1.0F - progress * 0.45F));
            context.fill(0, y, width, Math.min(height, y + thickness), (alpha << 24) | 0x87949A);
        }

        // Dark peripheral falloff frames the scene without replacing the center.
        int maxX = Math.max(8, width / 9);
        int maxY = Math.max(8, height / 9);
        for (int band = 0; band < BANDS; band++) {
            float progress = (band + 1.0F) / BANDS;
            int insetX = Math.round(maxX * (1.0F - progress));
            int insetY = Math.round(maxY * (1.0F - progress));
            int alpha = Math.min(86, Math.round(2.0F + 24.0F * strength * progress * progress));
            int color = (alpha << 24) | 0x05080A;
            context.fill(0, 0, Math.max(1, insetX), height, color);
            context.fill(width - Math.max(1, insetX), 0, width, height, color);
            context.fill(0, 0, width, Math.max(1, insetY), color);
            context.fill(0, height - Math.max(1, insetY), width, height, color);
        }
    }
}
