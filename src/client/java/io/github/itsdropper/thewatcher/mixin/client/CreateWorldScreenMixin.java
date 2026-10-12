package io.github.itsdropper.thewatcher.mixin.client;

import io.github.itsdropper.thewatcher.WatcherWorldOptions;
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
        int rowY = screen.height - 56;

        screen.addDrawableChild(ButtonWidget.builder(
                watcherModeLabel(),
                button -> {
                    WatcherWorldOptions.WATCHER_MODE_ENABLED = !WatcherWorldOptions.WATCHER_MODE_ENABLED;
                    button.setMessage(watcherModeLabel());
                })
                .dimensions(screen.width / 2 - 204, rowY, 200, 20)
                .build());

        screen.addDrawableChild(ButtonWidget.builder(
                runLengthLabel(),
                button -> {
                    WatcherWorldOptions.cycleRunLength();
                    button.setMessage(runLengthLabel());
                })
                .dimensions(screen.width / 2 + 4, rowY, 200, 20)
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
