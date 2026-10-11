package io.github.itsdropper.thewatcher.client;

import io.github.itsdropper.thewatcher.TheWatcher;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class WatcherSettingsScreen extends Screen {
    private static final int[] RENDER_DISTANCE_OPTIONS = {4, 6, 8, 10, 12, 16, 20, 24, 32};

    private final Screen parent;

    public WatcherSettingsScreen(Screen parent) {
        super(Text.literal("The Watcher Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int top = this.height / 4 + 4;

        // Use Minecraft's real render-distance option as the source of truth.
        int currentViewDistance = MinecraftClient.getInstance().options.getViewDistance().getValue();
        TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS = currentViewDistance;

        addDrawableChild(ButtonWidget.builder(toggleText("Dread effects", TheWatcher.DREAD_EFFECTS_ENABLED), button -> {
            TheWatcher.DREAD_EFFECTS_ENABLED = !TheWatcher.DREAD_EFFECTS_ENABLED;
            button.setMessage(toggleText("Dread effects", TheWatcher.DREAD_EFFECTS_ENABLED));
        }).dimensions(centerX - 110, top, 220, 20).build());

        addDrawableChild(ButtonWidget.builder(toggleText("Night darkness", TheWatcher.NIGHT_DARKNESS_ENABLED), button -> {
            TheWatcher.NIGHT_DARKNESS_ENABLED = !TheWatcher.NIGHT_DARKNESS_ENABLED;
            button.setMessage(toggleText("Night darkness", TheWatcher.NIGHT_DARKNESS_ENABLED));
        }).dimensions(centerX - 110, top + 26, 220, 20).build());

        addDrawableChild(ButtonWidget.builder(toggleText("Unpredictable teleporting", TheWatcher.TELEPORTS_ENABLED), button -> {
            TheWatcher.TELEPORTS_ENABLED = !TheWatcher.TELEPORTS_ENABLED;
            button.setMessage(toggleText("Unpredictable teleporting", TheWatcher.TELEPORTS_ENABLED));
        }).dimensions(centerX - 110, top + 52, 220, 20).build());

        addDrawableChild(ButtonWidget.builder(renderDistanceText(currentViewDistance), button -> {
            int current = MinecraftClient.getInstance().options.getViewDistance().getValue();
            int next = nextRenderDistance(current);
            MinecraftClient.getInstance().options.getViewDistance().setValue(next);
            TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS =
                    MinecraftClient.getInstance().options.getViewDistance().getValue();
            button.setMessage(renderDistanceText(TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS));
        }).dimensions(centerX - 110, top + 78, 220, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> this.client.setScreen(parent))
                .dimensions(centerX - 100, top + 112, 200, 20).build());
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
    public void close() {
        this.client.setScreen(parent);
    }
}
