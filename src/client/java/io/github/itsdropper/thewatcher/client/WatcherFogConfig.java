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
            case LOW -> 0.88F;
            case MEDIUM -> 0.75F;
            case HIGH -> 0.58F;
            case ULTRA -> 0.43F;
        };
        return Math.max(0.0F, original * scale * (isNight() ? 0.92F : 1.0F));
    }

    public static float adjustEnd(float original) {
        if (!shouldApply()) return original;
        float scale = switch (getDetail()) {
            case OFF -> 1.0F;
            case LOW -> 0.96F;
            case MEDIUM -> 0.90F;
            case HIGH -> 0.80F;
            case ULTRA -> 0.68F;
        };
        MinecraftClient client = MinecraftClient.getInstance();
        if (isNight()) scale *= 0.88F;
        if (client.world != null && client.world.isRaining()) scale *= 0.93F;
        return Math.max(8.0F, original * scale);
    }

    public static float colorBlendWeight() {
        if (!shouldApply()) return 0.0F;
        return switch (getDetail()) {
            case OFF -> 0.0F;
            case LOW -> 0.10F;
            case MEDIUM -> 0.18F;
            case HIGH -> 0.28F;
            case ULTRA -> 0.38F;
        } + (isNight() ? 0.07F : 0.0F);
    }

    public static float fogTargetRed() {
        return isNight() ? 0.035F : 0.57F;
    }

    public static float fogTargetGreen() {
        return isNight() ? 0.045F : 0.59F;
    }

    public static float fogTargetBlue() {
        return isNight() ? 0.070F : 0.61F;
    }

    public static float blendColor(float original, float target) {
        float weight = colorBlendWeight();
        return original + (target - original) * weight;
    }

    private static boolean isNight() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return false;
        long time = Math.floorMod(client.world.getTimeOfDay(), 24_000L);
        return time >= 13_000L && time < 23_000L;
    }
}
