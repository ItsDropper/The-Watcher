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
    private int panelLeft;
    private int panelRight;
    private int panelTop;
    private int contentTop;

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

        int panelWidth = Math.min(360, this.width - 16);
        panelLeft = (this.width - panelWidth) / 2;
        panelRight = panelLeft + panelWidth;
        panelTop = 5;
        contentTop = 54;
        int tabWidth = Math.min(112, (panelWidth - 12) / 2);
        int tabGap = 6;
        int tabsLeft = this.width / 2 - tabWidth - tabGap / 2;

        addDrawableChild(ButtonWidget.builder(Text.literal(page == 0 ? "01  /  GAMEPLAY" : "GAMEPLAY"),
                button -> openPage(0))
                .dimensions(tabsLeft, 30, tabWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(page == 1 ? "02  /  LOCAL AI" : "LOCAL AI"),
                button -> openPage(1))
                .dimensions(this.width / 2 + tabGap / 2, 30, tabWidth, 20).build());

        if (page == 0) {
            initGameplay();
        } else {
            initLocalAi();
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("SAVE  /  EXIT"), button -> {
            saveAiSettings();
            DesktopAnomalyConfig.save();
            this.client.setScreen(parent);
        }).dimensions(this.width / 2 - 78, this.height - 29, 156, 20).build());
    }

    private void initGameplay() {
        int gap = 6;
        int colWidth = (panelRight - panelLeft - 14 - gap) / 2;
        int leftX = panelLeft + 7;
        int rightX = leftX + colWidth + gap;
        int rowGap = 26;

        int currentViewDistance = MinecraftClient.getInstance().options.getViewDistance().getValue();
        TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS = currentViewDistance;

        addDrawableChild(ButtonWidget.builder(toggleText("DREAD EFFECTS", TheWatcher.DREAD_EFFECTS_ENABLED), button -> {
            TheWatcher.DREAD_EFFECTS_ENABLED = !TheWatcher.DREAD_EFFECTS_ENABLED;
            button.setMessage(toggleText("DREAD EFFECTS", TheWatcher.DREAD_EFFECTS_ENABLED));
        }).dimensions(leftX, contentTop, colWidth, 20).build());

        addDrawableChild(ButtonWidget.builder(toggleText("NIGHT DARKNESS", TheWatcher.NIGHT_DARKNESS_ENABLED), button -> {
            TheWatcher.NIGHT_DARKNESS_ENABLED = !TheWatcher.NIGHT_DARKNESS_ENABLED;
            button.setMessage(toggleText("NIGHT DARKNESS", TheWatcher.NIGHT_DARKNESS_ENABLED));
        }).dimensions(rightX, contentTop, colWidth, 20).build());

        addDrawableChild(ButtonWidget.builder(toggleText("STALKING", TheWatcher.TELEPORTS_ENABLED), button -> {
            TheWatcher.TELEPORTS_ENABLED = !TheWatcher.TELEPORTS_ENABLED;
            button.setMessage(toggleText("STALKING", TheWatcher.TELEPORTS_ENABLED));
        }).dimensions(leftX, contentTop + rowGap, colWidth, 20).build());

        addDrawableChild(ButtonWidget.builder(renderDistanceText(currentViewDistance), button -> {
            int next = nextRenderDistance(MinecraftClient.getInstance().options.getViewDistance().getValue());
            MinecraftClient.getInstance().options.getViewDistance().setValue(next);
            TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS =
                    MinecraftClient.getInstance().options.getViewDistance().getValue();
            button.setMessage(renderDistanceText(TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS));
        }).dimensions(rightX, contentTop + rowGap, colWidth, 20).build());

        addDrawableChild(ButtonWidget.builder(toggleText("DESKTOP ANOMALIES", DesktopAnomalyConfig.ENABLED), button -> {
            DesktopAnomalyConfig.ENABLED = !DesktopAnomalyConfig.ENABLED;
            DesktopAnomalyConfig.save();
            button.setMessage(toggleText("DESKTOP ANOMALIES", DesktopAnomalyConfig.ENABLED));
        }).dimensions(leftX, contentTop + rowGap * 2, colWidth, 20).build());

        addDrawableChild(ButtonWidget.builder(fogDetailText(), button -> {
            WatcherFogConfig.cycleDetail();
            button.setMessage(fogDetailText());
        }).dimensions(rightX, contentTop + rowGap * 2, colWidth, 20).build());
    }

    private void initLocalAi() {
        int fieldWidth = panelRight - panelLeft - 20;
        int left = panelLeft + 10;
        int top = contentTop - 1;

        addDrawableChild(ButtonWidget.builder(toggleText("LOCAL OLLAMA", WatcherOllamaConfig.ENABLED), button -> {
            WatcherOllamaConfig.ENABLED = !WatcherOllamaConfig.ENABLED;
            button.setMessage(toggleText("LOCAL OLLAMA", WatcherOllamaConfig.ENABLED));
        }).dimensions(left, top, fieldWidth, 20).build());

        endpointField = new TextFieldWidget(textRenderer, left, top + 42, fieldWidth, 18,
                Text.literal("Ollama endpoint"));
        endpointField.setMaxLength(240);
        endpointField.setText(WatcherOllamaConfig.ENDPOINT);
        endpointField.setPlaceholder(Text.literal("http://localhost:11434"));
        addDrawableChild(endpointField);

        modelField = new TextFieldWidget(textRenderer, left, top + 78, fieldWidth, 18,
                Text.literal("Ollama model"));
        modelField.setMaxLength(120);
        modelField.setText(WatcherOllamaConfig.MODEL);
        modelField.setPlaceholder(Text.literal("Installed model name"));
        addDrawableChild(modelField);

        promptField = new TextFieldWidget(textRenderer, left, top + 114, fieldWidth, 18,
                Text.literal("System prompt"));
        promptField.setMaxLength(1800);
        promptField.setText(WatcherOllamaConfig.SYSTEM_PROMPT);
        promptField.setPlaceholder(Text.literal("Horror director system prompt"));
        addDrawableChild(promptField);

        addDrawableChild(ButtonWidget.builder(promptPresetText(), button -> {
            WatcherOllamaConfig.cyclePromptPreset();
            promptField.setText(WatcherOllamaConfig.SYSTEM_PROMPT);
            button.setMessage(promptPresetText());
        }).dimensions(left, top + 139, fieldWidth, 20).build());
    }

    private void openPage(int nextPage) {
        if (nextPage == page) return;
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
        for (int option : RENDER_DISTANCE_OPTIONS) {
            if (option > current) return option;
            if (option == current) {
                int index = java.util.Arrays.binarySearch(RENDER_DISTANCE_OPTIONS, current);
                return RENDER_DISTANCE_OPTIONS[(index + 1) % RENDER_DISTANCE_OPTIONS.length];
            }
        }
        return RENDER_DISTANCE_OPTIONS[0];
    }

    private static Text promptPresetText() {
        return Text.literal("PROMPT PRESET  /  " + (WatcherOllamaConfig.getPromptPresetIndex() + 1) + " OF 3");
    }

    private static Text fogDetailText() {
        return Text.literal("ATMOSPHERE  /  " + WatcherFogConfig.label());
    }

    private static Text renderDistanceText(int chunks) {
        return Text.literal("RENDER  /  " + chunks + " CHUNKS");
    }

    private static Text toggleText(String name, boolean enabled) {
        return Text.literal(name + "  /  " + (enabled ? "ON" : "OFF"));
    }

    @Override
    protected void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        super.renderBackground(context, mouseX, mouseY, delta);
        context.fill(0, 0, this.width, this.height, 0xB804080A);
        context.fill(panelLeft, panelTop, panelRight, this.height - 5, 0xE90A1012);
        context.fill(panelLeft, panelTop, panelRight, panelTop + 2, 0xFF6A3437);
        context.fill(panelLeft, panelTop + 2, panelLeft + 2, this.height - 5, 0xFF6A3437);
        context.fill(panelLeft + 7, 57, panelRight - 7, 58, 0xFF273437);
        context.fill(panelLeft + 7, this.height - 38, panelRight - 7, this.height - 37, 0xFF273437);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int centerX = this.width / 2;
        context.drawCenteredTextWithShadow(textRenderer, "T H E   W A T C H E R", centerX, 10, 0xFFE0D3CF);
        context.drawCenteredTextWithShadow(textRenderer,
                page == 0 ? "FIELD CONDITIONS  /  SURVIVAL SYSTEMS" : "LOCAL MODEL  /  OPTIONAL DIRECTOR",
                centerX, 20, 0xFF829194);

        if (page == 1) {
            int left = panelLeft + 10;
            int top = contentTop + 27;
            context.drawTextWithShadow(textRenderer, "ENDPOINT", left, top, 0xFFB4C0C0);
            context.drawTextWithShadow(textRenderer, "MODEL", left, top + 36, 0xFFB4C0C0);
            context.drawTextWithShadow(textRenderer, "SYSTEM PROMPT", left, top + 72, 0xFFB4C0C0);
        } else {
            context.drawTextWithShadow(textRenderer, "ATMOSPHERE / NIGHT / ENCOUNTERS",
                    panelLeft + 10, this.height - 54, 0xFF829194);
        }
    }

    @Override
    public void close() {
        saveAiSettings();
        DesktopAnomalyConfig.save();
        this.client.setScreen(parent);
    }
}
