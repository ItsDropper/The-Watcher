package io.github.itsdropper.thewatcher;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Persistent index of camera positions; entries remain when their chunks unload. */
public final class CameraRegistry {
    private static final Map<String, CameraEntry> CAMERAS = new LinkedHashMap<>();
    private static Path loadedFile;

    private CameraRegistry() {
    }

    public static synchronized void register(ServerWorld world, BlockPos pos) {
        Path file = fileFor(world);
        loadIfNeeded(file);
        String dimension = world.getRegistryKey().getValue().toString();
        String key = key(dimension, pos);
        if (!CAMERAS.containsKey(key)) {
            CAMERAS.put(key, new CameraEntry(dimension, pos.toImmutable()));
            save();
        }
    }

    public static synchronized void remove(ServerWorld world, BlockPos pos) {
        Path file = fileFor(world);
        loadIfNeeded(file);
        if (CAMERAS.remove(key(world.getRegistryKey().getValue().toString(), pos)) != null) {
            save();
        }
    }

    public static synchronized List<BlockPos> getCameras(ServerWorld world) {
        loadIfNeeded(fileFor(world));
        String dimension = world.getRegistryKey().getValue().toString();
        return CAMERAS.values().stream()
                .filter(entry -> entry.dimension().equals(dimension))
                .map(entry -> entry.pos().toImmutable())
                .sorted(Comparator.comparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ))
                .toList();
    }

    private static Path fileFor(ServerWorld world) {
        return world.getServer().getSavePath(WorldSavePath.ROOT).resolve("the-watcher-cameras.properties");
    }

    private static String key(String dimension, BlockPos pos) {
        return dimension + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
    }

    private static void loadIfNeeded(Path file) {
        if (file.equals(loadedFile)) return;
        CAMERAS.clear();
        loadedFile = file;
        if (!Files.isRegularFile(file)) return;

        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
            for (String value : properties.stringPropertyNames()) {
                try {
                    String[] parts = properties.getProperty(value).split("\\|", 4);
                    if (parts.length != 4) continue;
                    BlockPos pos = new BlockPos(Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                    CAMERAS.put(key(parts[0], pos), new CameraEntry(parts[0], pos));
                } catch (IllegalArgumentException ignored) {
                    TheWatcher.LOGGER.warn("Ignoring malformed camera registry entry: {}", value);
                }
            }
        } catch (IOException exception) {
            TheWatcher.LOGGER.warn("Could not load the camera registry from {}", file, exception);
        }
    }

    private static void save() {
        if (loadedFile == null) return;
        Properties properties = new Properties();
        int index = 0;
        for (CameraEntry entry : CAMERAS.values()) {
            BlockPos pos = entry.pos();
            properties.setProperty("camera." + index++, entry.dimension() + "|"
                    + pos.getX() + "|" + pos.getY() + "|" + pos.getZ());
        }
        try {
            Files.createDirectories(loadedFile.getParent());
            try (var writer = Files.newBufferedWriter(loadedFile, StandardCharsets.UTF_8)) {
                properties.store(writer, "The Watcher persistent camera positions");
            }
        } catch (IOException exception) {
            TheWatcher.LOGGER.warn("Could not save the camera registry to {}", loadedFile, exception);
        }
    }

    private record CameraEntry(String dimension, BlockPos pos) {
    }
}
