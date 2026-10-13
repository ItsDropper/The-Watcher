package io.github.itsdropper.thewatcher.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Client-only fog quality and atmosphere controls. The renderer reads a few cached
 * values per fog pass; no particles, extra geometry, or per-frame allocations.
 */
public final class WatcherFogConfig {
    public enum Detail {
        OFF, LOW, MEDIUM, HIGH, ULTRA;

        public Detail next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private static final Path CONFIG_PATH = net.fabricmc.loader.api.FabricLoader.getInstance()
            .getConfigDir().resolve("the-watcher-fog.properties");
    private static Detail detail = Detail.HIGH;
    private static boolean loaded;

    private WatcherFogConfig() {}

    public static void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.isRegularFile(CONFIG_PATH)) return;
        Properties properties = new Properties();
        try (var input = Files.newInputStream(CONFIG_PATH)) {
            properties.load(input);
            detail = Detail.valueOf(properties.getProperty("detail", "HIGH").trim().toUpperCase());
        } catch (IOException | IllegalArgumentException exception) {
            detail = Detail.HIGH;
        }
    }

    public static Detail getDetail() {
        load();
        return detail;
    }

    public static String label() {
        return getDetail().name();
    }

    public static void cycleDetail() {
        load();
        detail = detail.next();
        save();
    }

    public static void save() {
        Properties properties = new Properties();
        properties.setProperty("detail", detail.name());
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (var output = Files.newOutputStream(CONFIG_PATH)) {
                properties.store(output, "The Watcher client fog quality");
            }
        } catch (IOException exception) {
            // Keep the current in-memory setting usable if the config directory is read-only.
        }
    }

    public static boolean shouldApply() {
        if (getDetail() == Detail.OFF) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.world.getRegistryKey() != World.OVERWORLD) return false;
        if (client.gameRenderer == null || client.gameRenderer.getCamera() == null) return false;
        return "NONE".equals(client.gameRenderer.getCamera().getSubmersionType().name());
    }

    public static float adjustStart(float original) {
        if (!shouldApply()) return original;
        float scale = switch (getDetail()) {
            case OFF -> 1.0F;
            case LOW -> 0.78F;
            case MEDIUM -> 0.62F;
            case HIGH -> 0.48F;
            case ULTRA -> 0.36F;
        };
        // Keep the transition broad enough to avoid a hard-looking fog wall at chunk edges.
        return Math.max(0.0F, original * scale * (1.0F - 0.03F * nightFactor()));
    }

    public static float adjustEnd(float original) {
        if (!shouldApply()) return original;
        float scale = switch (getDetail()) {
            case OFF -> 1.0F;
            case LOW -> 0.97F;
            case MEDIUM -> 0.94F;
            case HIGH -> 0.90F;
            case ULTRA -> 0.84F;
        };
        MinecraftClient client = MinecraftClient.getInstance();
        // Avoid pulling the opaque fog cutoff far inside the loaded terrain boundary.
        scale *= 1.0F - 0.05F * nightFactor();
        if (client.world != null && client.world.isRaining()) scale *= 0.97F;
        return Math.max(8.0F, original * scale);
    }

    /** Hide the vanilla sun while any atmospheric fog preset is active. */
    public static boolean shouldHideSun() {
        return shouldApply();
    }

    public static float colorBlendWeight() {
        if (!shouldApply()) return 0.0F;
        return switch (getDetail()) {
            case OFF -> 0.0F;
            case LOW -> 0.24F;
            case MEDIUM -> 0.38F;
            case HIGH -> 0.54F;
            case ULTRA -> 0.68F;
        } + (0.08F * nightFactor());
    }

    public static float fogTargetRed() {
        return lerp(0.25F, 0.018F, nightFactor());
    }

    public static float fogTargetGreen() {
        return lerp(0.275F, 0.024F, nightFactor());
    }

    public static float fogTargetBlue() {
        return lerp(0.30F, 0.040F, nightFactor());
    }

    /**
     * Daylight exposure reduction for the vanilla lightmap. This changes the
     * brightness of the actual terrain, not just how far the player can see.
     * LOW is subtle; ULTRA is deliberately oppressive.
     */
    public static float daylightMultiplier() {
        if (!shouldApply()) return 1.0F;
        float strength = switch (getDetail()) {
            case OFF -> 0.0F;
            case LOW -> 0.08F;
            case MEDIUM -> 0.17F;
            case HIGH -> 0.28F;
            case ULTRA -> 0.40F;
        };
        MinecraftClient client = MinecraftClient.getInstance();
        float weatherFactor = client.world != null && client.world.isRaining() ? 1.18F : 1.0F;
        return 1.0F - strength * dayFactor() * weatherFactor;
    }

    /** Smoothly fades the daytime grade out around dusk and dawn. */
    private static float dayFactor() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return 0.0F;
        long time = Math.floorMod(client.world.getTimeOfDay(), 24_000L);
        if (time >= 13_000L && time < 23_000L) return 0.0F;
        if (time >= 11_000L && time < 13_000L) {
            return 1.0F - smoothstep((time - 11_000L) / 2_000.0F);
        }
        if (time >= 23_000L) {
            return smoothstep((time - 23_000L) / 1_000.0F);
        }
        if (time < 1_000L) return smoothstep(time / 1_000.0F);
        if (time >= 9_000L && time < 11_000L) {
            return 1.0F - smoothstep((time - 9_000L) / 2_000.0F);
        }
        return 1.0F;
    }

    public static float blendColor(float original, float target) {
        float weight = colorBlendWeight();
        return original + (target - original) * weight;
    }

    private static float nightFactor() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return 0.0F;
        long time = Math.floorMod(client.world.getTimeOfDay(), 24_000L);
        if (time >= 12_000L && time < 14_000L) {
            return smoothstep((time - 12_000L) / 2_000.0F);
        }
        if (time >= 14_000L && time < 22_000L) return 1.0F;
        if (time >= 22_000L) {
            return 1.0F - smoothstep((time - 22_000L) / 2_000.0F);
        }
        return 0.0F;
    }

    private static float smoothstep(float value) {
        float t = Math.max(0.0F, Math.min(1.0F, value));
        return t * t * (3.0F - 2.0F * t);
    }

    private static float lerp(float start, float end, float amount) {
        return start + (end - start) * amount;
    }
}
