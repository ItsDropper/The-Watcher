package io.github.itsdropper.thewatcher.client;

import io.github.itsdropper.thewatcher.TheWatcher;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class WatcherSettingsScreen extends Screen {
    private final Screen parent;

    public WatcherSettingsScreen(Screen parent) {
        super(Text.literal("The Watcher Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int top = this.height / 4 + 12;

        addDrawableChild(ButtonWidget.builder(toggleText("Dread effects", TheWatcher.DREAD_EFFECTS_ENABLED), button -> {
            TheWatcher.DREAD_EFFECTS_ENABLED = !TheWatcher.DREAD_EFFECTS_ENABLED;
            button.setMessage(toggleText("Dread effects", TheWatcher.DREAD_EFFECTS_ENABLED));
        }).dimensions(centerX - 100, top, 200, 20).build());

        addDrawableChild(ButtonWidget.builder(toggleText("Night darkness", TheWatcher.NIGHT_DARKNESS_ENABLED), button -> {
            TheWatcher.NIGHT_DARKNESS_ENABLED = !TheWatcher.NIGHT_DARKNESS_ENABLED;
            button.setMessage(toggleText("Night darkness", TheWatcher.NIGHT_DARKNESS_ENABLED));
        }).dimensions(centerX - 100, top + 26, 200, 20).build());

        addDrawableChild(ButtonWidget.builder(toggleText("Unpredictable teleporting", TheWatcher.TELEPORTS_ENABLED), button -> {
            TheWatcher.TELEPORTS_ENABLED = !TheWatcher.TELEPORTS_ENABLED;
            button.setMessage(toggleText("Unpredictable teleporting", TheWatcher.TELEPORTS_ENABLED));
        }).dimensions(centerX - 100, top + 52, 200, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> this.client.setScreen(parent))
                .dimensions(centerX - 100, top + 84, 200, 20).build());
    }

    private static Text toggleText(String name, boolean enabled) {
        return Text.literal(name + ": " + (enabled ? "ON" : "OFF"));
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
