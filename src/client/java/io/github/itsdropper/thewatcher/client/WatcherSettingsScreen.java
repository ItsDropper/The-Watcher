package io.github.itsdropper.thewatcher.client;

import io.github.itsdropper.thewatcher.TheWatcher;
import io.github.itsdropper.thewatcher.WatcherOllamaConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public final class WatcherSettingsScreen extends Screen {
    private static final int[] RENDER_DISTANCE_OPTIONS = {4, 6, 8, 10, 12, 16, 20, 24, 32};

    private final Screen parent;
    private TextFieldWidget endpointField;
    private TextFieldWidget modelField;
    private TextFieldWidget promptField;

    public WatcherSettingsScreen(Screen parent) {
        super(Text.literal("The Watcher Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        WatcherOllamaConfig.load();
        int centerX = this.width / 2;
        int top = Math.max(12, this.height / 4 - 12);

        int currentViewDistance = MinecraftClient.getInstance().options.getViewDistance().getValue();
        TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS = currentViewDistance;

        addDrawableChild(ButtonWidget.builder(toggleText("Dread effects", TheWatcher.DREAD_EFFECTS_ENABLED), button -> {
            TheWatcher.DREAD_EFFECTS_ENABLED = !TheWatcher.DREAD_EFFECTS_ENABLED;
            button.setMessage(toggleText("Dread effects", TheWatcher.DREAD_EFFECTS_ENABLED));
        }).dimensions(centerX - 110, top, 220, 20).build());

        addDrawableChild(ButtonWidget.builder(toggleText("Night darkness", TheWatcher.NIGHT_DARKNESS_ENABLED), button -> {
            TheWatcher.NIGHT_DARKNESS_ENABLED = !TheWatcher.NIGHT_DARKNESS_ENABLED;
            button.setMessage(toggleText("Night darkness", TheWatcher.NIGHT_DARKNESS_ENABLED));
        }).dimensions(centerX - 110, top + 24, 220, 20).build());

        addDrawableChild(ButtonWidget.builder(toggleText("Unpredictable teleporting", TheWatcher.TELEPORTS_ENABLED), button -> {
            TheWatcher.TELEPORTS_ENABLED = !TheWatcher.TELEPORTS_ENABLED;
            button.setMessage(toggleText("Unpredictable teleporting", TheWatcher.TELEPORTS_ENABLED));
        }).dimensions(centerX - 110, top + 48, 220, 20).build());

        addDrawableChild(ButtonWidget.builder(renderDistanceText(currentViewDistance), button -> {
            int current = MinecraftClient.getInstance().options.getViewDistance().getValue();
            int next = nextRenderDistance(current);
            MinecraftClient.getInstance().options.getViewDistance().setValue(next);
            TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS =
                    MinecraftClient.getInstance().options.getViewDistance().getValue();
            button.setMessage(renderDistanceText(TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS));
        }).dimensions(centerX - 110, top + 72, 220, 20).build());

        addDrawableChild(ButtonWidget.builder(toggleText("Local Ollama AI", WatcherOllamaConfig.ENABLED), button -> {
            WatcherOllamaConfig.ENABLED = !WatcherOllamaConfig.ENABLED;
            button.setMessage(toggleText("Local Ollama AI", WatcherOllamaConfig.ENABLED));
        }).dimensions(centerX - 110, top + 96, 220, 20).build());

        endpointField = new TextFieldWidget(textRenderer, centerX - 110, top + 122, 220, 20,
                Text.literal("Ollama endpoint"));
        endpointField.setMaxLength(240);
        endpointField.setText(WatcherOllamaConfig.ENDPOINT);
        endpointField.setPlaceholder(Text.literal("http://localhost:11434"));
        addDrawableChild(endpointField);

        modelField = new TextFieldWidget(textRenderer, centerX - 110, top + 146, 220, 20,
                Text.literal("Ollama model"));
        modelField.setMaxLength(120);
        modelField.setText(WatcherOllamaConfig.MODEL);
        modelField.setPlaceholder(Text.literal("Model name, e.g. llama3.2"));
        addDrawableChild(modelField);

        promptField = new TextFieldWidget(textRenderer, centerX - 110, top + 170, 220, 20,
                Text.literal("System prompt"));
        promptField.setMaxLength(1800);
        promptField.setText(WatcherOllamaConfig.SYSTEM_PROMPT);
        promptField.setPlaceholder(Text.literal("System prompt for the horror director"));
        addDrawableChild(promptField);

        addDrawableChild(ButtonWidget.builder(Text.literal("Save AI settings"), button -> saveAiSettings())
                .dimensions(centerX - 110, top + 196, 220, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> {
            saveAiSettings();
            this.client.setScreen(parent);
        }).dimensions(centerX - 100, top + 222, 200, 20).build());
    }

    private void saveAiSettings() {
        if (endpointField != null) WatcherOllamaConfig.ENDPOINT = endpointField.getText();
        if (modelField != null) WatcherOllamaConfig.MODEL = modelField.getText();
        if (promptField != null) WatcherOllamaConfig.SYSTEM_PROMPT = promptField.getText();
        WatcherOllamaConfig.save();
    }

    private static int nextRenderDistance(int current) {
        for (int i = 0; i < RENDER_DISTANCE_OPTIONS.length; i++) {
            if (RENDER_DISTANCE_OPTIONS[i] == current) {
                return RENDER_DISTANCE_OPTIONS[(i + 1) % RENDER_DISTANCE_OPTIONS.length];
            }
            if (RENDER_DISTANCE_OPTIONS[i] > current) {
                return RENDER_DISTANCE_OPTIONS[i];
            }
        }
        return RENDER_DISTANCE_OPTIONS[0];
    }

    private static Text renderDistanceText(int chunks) {
        return Text.literal("Render distance: " + chunks + " chunks");
    }

    private static Text toggleText(String name, boolean enabled) {
        return Text.literal(name + ": " + (enabled ? "ON" : "OFF"));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int centerX = this.width / 2;
        int top = Math.max(12, this.height / 4 - 12);
        context.drawTextWithShadow(textRenderer, "Endpoint", centerX - 110, top + 113, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, "Model", centerX - 110, top + 137, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, "System prompt", centerX - 110, top + 161, 0xFFFFFF);
    }

    @Override
    public void close() {
        saveAiSettings();
        this.client.setScreen(parent);
    }
}