package io.github.itsdropper.thewatcher;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Local-only Ollama settings. No cloud endpoint is used unless explicitly configured. */
public final class WatcherOllamaConfig {
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("the-watcher-ollama.properties");
    private static boolean loaded;

    public static volatile boolean ENABLED = false;
    public static volatile String ENDPOINT = "http://localhost:11434";
    public static volatile String MODEL = "llama3.2";
    public static volatile String SYSTEM_PROMPT = "You are the hidden horror director for The Watcher, a Minecraft psychological horror mod. Create slow, restrained dread. Never use constant jumpscares, unfair unavoidable attacks, real-world threats, or content outside the game. Adapt to the current game state, vary events, and leave long quiet gaps. Return only a JSON object with event (one of whisper, darkness, footsteps, observe, stalk, retreat, none) and message (a short in-game cryptic line, at most 100 characters). Use none when silence is more frightening. Never request commands, code execution, files, or network actions.";
    private static int promptPresetIndex;

    private static final String[] PROMPT_PRESETS = {
            "You are the hidden horror director for The Watcher, a Minecraft psychological horror mod. Create slow, restrained dread. Never use constant jumpscares, unfair unavoidable attacks, real-world threats, or content outside the game. Adapt to the current game state, vary events, and leave long quiet gaps. Return only a JSON object with event (one of whisper, darkness, footsteps, observe, stalk, retreat, none) and message (a short in-game cryptic line, at most 100 characters). Use none when silence is more frightening. Never request commands, code execution, files, or network actions.",
            "You are an environmental storyteller for The Watcher. Make the world feel abandoned and observant: distant footsteps, empty camps, strange silence, and short cryptic clues. Prefer atmosphere over combat, do not repeat recent ideas, and allow long periods with no event. Return only JSON with event (whisper, darkness, footsteps, observe, stalk, retreat, none) and message (under 100 characters). Never request commands, code, files, or network actions.",
            "You are the late-run horror director for The Watcher. Increase pressure gradually but preserve fair counterplay and quiet intervals. Choose only subtle footsteps, brief darkness, a short in-game warning, a bounded Watcher phase (observe, stalk, retreat), or silence. Never force unavoidable damage or claim anything outside Minecraft is happening. Return only JSON with event (whisper, darkness, footsteps, observe, stalk, retreat, none) and message (under 100 characters). Never request commands, code, files, or network actions."
    };

    private WatcherOllamaConfig() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isRegularFile(CONFIG_PATH)) {
            save();
            return;
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
            properties.load(reader);
            ENABLED = Boolean.parseBoolean(properties.getProperty("enabled", "false"));
            ENDPOINT = properties.getProperty("endpoint", ENDPOINT).trim();
            MODEL = properties.getProperty("model", MODEL).trim();
            SYSTEM_PROMPT = properties.getProperty("systemPrompt", SYSTEM_PROMPT).trim();
        } catch (IOException exception) {
            TheWatcher.LOGGER.warn("Could not load Ollama settings from {}", CONFIG_PATH, exception);
        }
        validate();
    }

    public static synchronized void cyclePromptPreset() {
        promptPresetIndex = (promptPresetIndex + 1) % PROMPT_PRESETS.length;
        SYSTEM_PROMPT = PROMPT_PRESETS[promptPresetIndex];
    }

    public static synchronized int getPromptPresetIndex() {
        return promptPresetIndex;
    }

    public static synchronized void save() {
        validate();
        Properties properties = new Properties();
        properties.setProperty("enabled", Boolean.toString(ENABLED));
        properties.setProperty("endpoint", ENDPOINT);
        properties.setProperty("model", MODEL);
        properties.setProperty("systemPrompt", SYSTEM_PROMPT);
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                properties.store(writer, "The Watcher - optional local Ollama integration");
            }
            loaded = true;
        } catch (IOException exception) {
            TheWatcher.LOGGER.warn("Could not save Ollama settings to {}", CONFIG_PATH, exception);
        }
    }

    private static void validate() {
        ENDPOINT = trimOr(ENDPOINT, "http://localhost:11434");
        while (ENDPOINT.endsWith("/")) {
            ENDPOINT = ENDPOINT.substring(0, ENDPOINT.length() - 1);
        }
        MODEL = trimOr(MODEL, "llama3.2");
        SYSTEM_PROMPT = trimOr(SYSTEM_PROMPT, "You are the hidden horror director for The Watcher. Return JSON with event and message.");
        if (ENDPOINT.length() > 240) ENDPOINT = ENDPOINT.substring(0, 240);
        if (MODEL.length() > 120) MODEL = MODEL.substring(0, 120);
        if (SYSTEM_PROMPT.length() > 1800) SYSTEM_PROMPT = SYSTEM_PROMPT.substring(0, 1800);
    }

    private static String trimOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}