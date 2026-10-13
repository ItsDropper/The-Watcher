package io.github.itsdropper.thewatcher.client;

import io.github.itsdropper.thewatcher.BloodMoonSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Client-only atmosphere controls. Fog and exposure are applied through the
 * vanilla renderer/lightmap, without particles or per-frame allocations.
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
                properties.store(output, "The Watcher client atmosphere quality");
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
            case LOW -> 0.76F;
            case MEDIUM -> 0.62F;
            case HIGH -> 0.48F;
            case ULTRA -> 0.34F;
        };
        // Start the atmospheric veil sooner, but keep a broad transition.
        return Math.max(0.0F, original * scale * (1.0F - 0.12F * nightFactor()));
    }

    public static float adjustEnd(float original) {
        if (!shouldApply()) return original;
        float scale = switch (getDetail()) {
            case OFF -> 1.0F;
            case LOW -> 0.90F;
            case MEDIUM -> 0.80F;
            case HIGH -> 0.68F;
            case ULTRA -> 0.52F;
        };
        MinecraftClient client = MinecraftClient.getInstance();
        scale *= 1.0F - 0.14F * nightFactor();
        if (client.world != null && client.world.isRaining()) scale *= 0.78F;
        return Math.max(12.0F, original * scale);
    }

    /** Hide the sun for the deliberately overcast Watcher sky. */
    public static boolean shouldHideSun() {
        return shouldApply();
    }

    public static float colorBlendWeight() {
        if (!shouldApply()) return 0.0F;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null && BloodMoonSystem.isActive(client.world)) return 0.96F;
        float base = switch (getDetail()) {
            case OFF -> 0.0F;
            case LOW -> 0.30F;
            case MEDIUM -> 0.46F;
            case HIGH -> 0.64F;
            case ULTRA -> 0.78F;
        };
        return Math.min(0.92F, base + 0.16F * nightFactor()
                + (client.world != null && client.world.isRaining() ? 0.16F : 0.0F));
    }

    public static float fogTargetRed() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null && BloodMoonSystem.isActive(client.world)) return 0.32F;
        return lerp(0.20F, 0.035F, nightFactor());
    }

    public static float fogTargetGreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null && BloodMoonSystem.isActive(client.world)) return 0.018F;
        return lerp(0.22F, 0.052F, nightFactor());
    }

    public static float fogTargetBlue() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != null && BloodMoonSystem.isActive(client.world)) return 0.025F;
        return lerp(0.24F, 0.075F, nightFactor());
    }

    /** Reduce sky-driven exposure while preserving useful light from torches. */
    public static float daylightMultiplier() {
        if (!shouldApply()) return 1.0F;
        float strength = switch (getDetail()) {
            case OFF -> 0.0F;
            case LOW -> 0.12F;
            case MEDIUM -> 0.28F;
            case HIGH -> 0.42F;
            case ULTRA -> 0.56F;
        };
        MinecraftClient client = MinecraftClient.getInstance();
        float weatherFactor = client.world != null && client.world.isRaining() ? 1.15F : 1.0F;
        return 1.0F - strength * dayFactor() * weatherFactor;
    }

    private static float dayFactor() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return 0.0F;
        long time = Math.floorMod(client.world.getTimeOfDay(), 24_000L);
        if (time >= 13_000L && time < 23_000L) return 0.0F;
        if (time >= 11_000L && time < 13_000L) {
            return 1.0F - smoothstep((time - 11_000L) / 2_000.0F);
        }
        if (time >= 23_000L) return smoothstep((time - 23_000L) / 1_000.0F);
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
        if (time >= 22_000L) return 1.0F - smoothstep((time - 22_000L) / 2_000.0F);
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
