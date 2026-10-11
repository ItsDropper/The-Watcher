package io.github.itsdropper.thewatcher.mixin.client;

import io.github.itsdropper.thewatcher.client.WatcherSettingsScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin {
    @Shadow
    protected abstract <T extends Element & Drawable & Selectable> T addDrawableChild(T drawableElement);

    @Inject(method = "init", at = @At("TAIL"))
    private void theWatcher$addSettingsButton(CallbackInfo ci) {
        GameMenuScreen screen = (GameMenuScreen) (Object) this;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("The Watcher Settings"),
                button -> MinecraftClient.getInstance().setScreen(new WatcherSettingsScreen(screen)))
                .dimensions(MinecraftClient.getInstance().getWindow().getScaledWidth() / 2 - 100,
                        MinecraftClient.getInstance().getWindow().getScaledHeight() / 4 + 96, 200, 20)
                .build());
    }
}
