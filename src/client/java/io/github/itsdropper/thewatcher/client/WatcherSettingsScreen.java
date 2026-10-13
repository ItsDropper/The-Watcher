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
    private final int page;
    private TextFieldWidget endpointField;
    private TextFieldWidget modelField;
    private TextFieldWidget promptField;

    public WatcherSettingsScreen(Screen parent) {
        this(parent, 0);
    }

    private WatcherSettingsScreen(Screen parent, int page) {
        super(Text.literal("The Watcher Settings"));
        this.parent = parent;
        this.page = page;
    }

    @Override
    protected void init() {
        WatcherOllamaConfig.load();
        DesktopAnomalyConfig.load();
        int centerX = this.width / 2;
        int top = Math.max(10, this.height / 5 - 8);
        int panelWidth = Math.min(280, this.width - 24);
        int left = centerX - panelWidth / 2;

        addDrawableChild(ButtonWidget.builder(Text.literal(page == 0 ? "[ Gameplay ]" : "Gameplay"),
                button -> openPage(0))
                .dimensions(centerX - 112, top, 108, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(page == 1 ? "[ Local AI ]" : "Local AI"),
                button -> openPage(1))
                .dimensions(centerX + 4, top, 108, 20).build());

        if (page == 0) {
            initGameplay(centerX, top + 36);
        } else {
            initLocalAi(centerX, left, panelWidth, top + 34);
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> {
            saveAiSettings();
            DesktopAnomalyConfig.save();
            this.client.setScreen(parent);
        }).dimensions(centerX - 80, this.height - 38, 160, 20).build());
    }

    private void initGameplay(int centerX, int top) {
        int currentViewDistance = MinecraftClient.getInstance().options.getViewDistance().getValue();
        TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS = currentViewDistance;

        addDrawableChild(ButtonWidget.builder(toggleText("Dread effects", TheWatcher.DREAD_EFFECTS_ENABLED), button -> {
            TheWatcher.DREAD_EFFECTS_ENABLED = !TheWatcher.DREAD_EFFECTS_ENABLED;
            button.setMessage(toggleText("Dread effects", TheWatcher.DREAD_EFFECTS_ENABLED));
        }).dimensions(centerX - 112, top, 224, 20).build());

        addDrawableChild(ButtonWidget.builder(toggleText("Night darkness", TheWatcher.NIGHT_DARKNESS_ENABLED), button -> {
            TheWatcher.NIGHT_DARKNESS_ENABLED = !TheWatcher.NIGHT_DARKNESS_ENABLED;
            button.setMessage(toggleText("Night darkness", TheWatcher.NIGHT_DARKNESS_ENABLED));
        }).dimensions(centerX - 112, top + 27, 224, 20).build());

        addDrawableChild(ButtonWidget.builder(toggleText("Unpredictable teleporting", TheWatcher.TELEPORTS_ENABLED), button -> {
            TheWatcher.TELEPORTS_ENABLED = !TheWatcher.TELEPORTS_ENABLED;
            button.setMessage(toggleText("Unpredictable teleporting", TheWatcher.TELEPORTS_ENABLED));
        }).dimensions(centerX - 112, top + 54, 224, 20).build());

        addDrawableChild(ButtonWidget.builder(renderDistanceText(currentViewDistance), button -> {
            int current = MinecraftClient.getInstance().options.getViewDistance().getValue();
            int next = nextRenderDistance(current);
            MinecraftClient.getInstance().options.getViewDistance().setValue(next);
            TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS =
                    MinecraftClient.getInstance().options.getViewDistance().getValue();
            button.setMessage(renderDistanceText(TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS));
        }).dimensions(centerX - 112, top + 81, 224, 20).build());

        addDrawableChild(ButtonWidget.builder(toggleText("Native desktop popups", DesktopAnomalyConfig.ENABLED), button -> {
            DesktopAnomalyConfig.ENABLED = !DesktopAnomalyConfig.ENABLED;
            DesktopAnomalyConfig.save();
            button.setMessage(toggleText("Native desktop popups", DesktopAnomalyConfig.ENABLED));
        }).dimensions(centerX - 112, top + 108, 224, 20).build());

        addDrawableChild(ButtonWidget.builder(fogDetailText(), button -> {
            WatcherFogConfig.cycleDetail();
            button.setMessage(fogDetailText());
        }).dimensions(centerX - 112, top + 135, 224, 20).build());
    }

    private void initLocalAi(int centerX, int left, int panelWidth, int top) {
        addDrawableChild(ButtonWidget.builder(toggleText("Local Ollama AI", WatcherOllamaConfig.ENABLED), button -> {
            WatcherOllamaConfig.ENABLED = !WatcherOllamaConfig.ENABLED;
            button.setMessage(toggleText("Local Ollama AI", WatcherOllamaConfig.ENABLED));
        }).dimensions(centerX - 112, top, 224, 20).build());

        endpointField = new TextFieldWidget(textRenderer, left, top + 42, panelWidth, 20,
                Text.literal("Ollama endpoint"));
        endpointField.setMaxLength(240);
        endpointField.setText(WatcherOllamaConfig.ENDPOINT);
        endpointField.setPlaceholder(Text.literal("http://localhost:11434"));
        addDrawableChild(endpointField);

        modelField = new TextFieldWidget(textRenderer, left, top + 88, panelWidth, 20,
                Text.literal("Ollama model"));
        modelField.setMaxLength(120);
        modelField.setText(WatcherOllamaConfig.MODEL);
        modelField.setPlaceholder(Text.literal("Installed model name, e.g. llama3.2"));
        addDrawableChild(modelField);

        promptField = new TextFieldWidget(textRenderer, left, top + 134, panelWidth, 20,
                Text.literal("System prompt"));
        promptField.setMaxLength(1800);
        promptField.setText(WatcherOllamaConfig.SYSTEM_PROMPT);
        promptField.setPlaceholder(Text.literal("Editable horror director system prompt"));
        addDrawableChild(promptField);

        addDrawableChild(ButtonWidget.builder(promptPresetText(), button -> {
            WatcherOllamaConfig.cyclePromptPreset();
            promptField.setText(WatcherOllamaConfig.SYSTEM_PROMPT);
            button.setMessage(promptPresetText());
        }).dimensions(centerX - 112, top + 166, 224, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Save AI settings"), button -> saveAiSettings())
                .dimensions(centerX - 112, top + 192, 224, 20).build());
    }

    private void openPage(int nextPage) {
        if (nextPage == page) {
            return;
        }
        saveAiSettings();
        DesktopAnomalyConfig.save();
        this.client.setScreen(new WatcherSettingsScreen(parent, nextPage));
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

    private static Text promptPresetText() {
        return Text.literal("System prompt preset: " + (WatcherOllamaConfig.getPromptPresetIndex() + 1) + "/3");
    }

    private static Text fogDetailText() {
        return Text.literal("Atmospheric fog: " + WatcherFogConfig.label());
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
        int top = Math.max(10, this.height / 5 - 8);
        context.drawCenteredTextWithShadow(textRenderer, "THE WATCHER", centerX, top - 24, 0xE0E0E0);
        context.drawCenteredTextWithShadow(textRenderer,
                page == 0 ? "Gameplay and encounter behaviour" : "Optional local model director",
                centerX, top + 25, 0xA0A0A0);

        if (page == 1) {
            int panelWidth = Math.min(280, this.width - 24);
            int left = centerX - panelWidth / 2;
            int fieldTop = top + 34;
            context.drawTextWithShadow(textRenderer, "Ollama endpoint", left, fieldTop + 31, 0xFFFFFF);
            context.drawTextWithShadow(textRenderer, "Model name", left, fieldTop + 77, 0xFFFFFF);
            context.drawTextWithShadow(textRenderer, "System prompt (single line)", left, fieldTop + 123, 0xFFFFFF);
            context.drawCenteredTextWithShadow(textRenderer,
                    "The model may choose only approved in-game events.",
                    centerX, this.height - 56, 0x909090);
        } else {
            context.drawCenteredTextWithShadow(textRenderer,
                    "Fog quality changes visuals only; Ultra uses no particles.",
                    centerX, this.height - 70, 0x909090);
            context.drawCenteredTextWithShadow(textRenderer,
                    "The Watcher learns from your movement and where you look.",
                    centerX, this.height - 56, 0x909090);
        }
    }

    @Override
    public void close() {
        saveAiSettings();
        DesktopAnomalyConfig.save();
        this.client.setScreen(parent);
    }
}