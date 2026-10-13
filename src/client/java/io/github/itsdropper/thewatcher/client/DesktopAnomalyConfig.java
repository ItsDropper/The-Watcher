package io.github.itsdropper.thewatcher.client;

import io.github.itsdropper.thewatcher.TheWatcher;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Explicit opt-in for native desktop horror effects. Disabled for new installations. */
public final class DesktopAnomalyConfig {
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("the-watcher-desktop.properties");
    private static boolean loaded;

    public static boolean ENABLED = false;

    private DesktopAnomalyConfig() {}

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.isRegularFile(CONFIG_PATH)) {
            save();
            return;
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
            properties.load(reader);
            ENABLED = Boolean.parseBoolean(properties.getProperty("enabled", "false"));
        } catch (IOException exception) {
            TheWatcher.LOGGER.warn("Could not load desktop horror settings from {}", CONFIG_PATH, exception);
            ENABLED = false;
        }
    }

    public static synchronized void save() {
        Properties properties = new Properties();
        properties.setProperty("enabled", Boolean.toString(ENABLED));
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                properties.store(writer, "The Watcher - native desktop horror effects; opt-in only");
            }
            loaded = true;
        } catch (IOException exception) {
            TheWatcher.LOGGER.warn("Could not save desktop horror settings to {}", CONFIG_PATH, exception);
        }
    }
}
