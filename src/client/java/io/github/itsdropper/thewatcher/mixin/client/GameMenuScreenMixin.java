package io.github.itsdropper.thewatcher.mixin.client;

import io.github.itsdropper.thewatcher.client.WatcherSettingsScreen;
import io.github.itsdropper.thewatcher.client.hosting.HostingScreen;
import io.github.itsdropper.thewatcher.mixin.client.ScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void theWatcher$addSettingsButton(CallbackInfo ci) {
        GameMenuScreen screen = (GameMenuScreen) (Object) this;
        ScreenAccessor accessor = (ScreenAccessor) (Object) screen;
        accessor.theWatcher$addDrawableChild(ButtonWidget.builder(Text.literal("The Watcher Settings"),
                button -> MinecraftClient.getInstance().setScreen(new WatcherSettingsScreen(screen)))
                .dimensions(MinecraftClient.getInstance().getWindow().getScaledWidth() / 2 - 100,
                        MinecraftClient.getInstance().getWindow().getScaledHeight() / 4 + 144, 200, 20)
                .build());

        accessor.theWatcher$addDrawableChild(ButtonWidget.builder(Text.literal("Host / Join World"), button ->
                MinecraftClient.getInstance().setScreen(new HostingScreen(screen)))
                .dimensions(MinecraftClient.getInstance().getWindow().getScaledWidth() / 2 - 100,
                        MinecraftClient.getInstance().getWindow().getScaledHeight() / 4 + 168, 200, 20)
                .build());
    }
}
