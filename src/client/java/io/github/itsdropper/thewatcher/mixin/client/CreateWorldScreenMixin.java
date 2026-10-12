package io.github.itsdropper.thewatcher.mixin.client;

import io.github.itsdropper.thewatcher.WatcherWorldOptions;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void theWatcher$addWorldOptions(CallbackInfo ci) {
        CreateWorldScreen screen = (CreateWorldScreen) (Object) this;
        ScreenAccessor accessor = (ScreenAccessor) (Object) screen;
        MinecraftClient client = MinecraftClient.getInstance();
        int width = client.getWindow().getScaledWidth();
        int rowY = client.getWindow().getScaledHeight() - 56;

        accessor.theWatcher$addDrawableChild(ButtonWidget.builder(
                watcherModeLabel(),
                button -> {
                    WatcherWorldOptions.WATCHER_MODE_ENABLED = !WatcherWorldOptions.WATCHER_MODE_ENABLED;
                    button.setMessage(watcherModeLabel());
                })
                .dimensions(width / 2 - 204, rowY, 200, 20)
                .build());

        accessor.theWatcher$addDrawableChild(ButtonWidget.builder(
                runLengthLabel(),
                button -> {
                    WatcherWorldOptions.cycleRunLength();
                    button.setMessage(runLengthLabel());
                })
                .dimensions(width / 2 + 4, rowY, 200, 20)
                .build());
    }

    private static Text watcherModeLabel() {
        return Text.literal("Watcher mode: "
                + (WatcherWorldOptions.WATCHER_MODE_ENABLED ? "ON" : "OFF"));
    }

    private static Text runLengthLabel() {
        return Text.literal("Run length: " + WatcherWorldOptions.getRunLengthLabel());
    }
}
