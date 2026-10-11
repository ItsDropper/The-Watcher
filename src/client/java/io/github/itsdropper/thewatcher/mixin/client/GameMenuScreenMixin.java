package io.github.itsdropper.thewatcher.mixin.client;

import io.github.itsdropper.thewatcher.client.WatcherSettingsScreen;
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
        screen.addDrawableChild(ButtonWidget.builder(Text.literal("The Watcher Settings"),
                button -> screen.getClient().setScreen(new WatcherSettingsScreen(screen)))
                .dimensions(screen.width / 2 - 100, screen.height / 4 + 96, 200, 20)
                .build());
    }
}
