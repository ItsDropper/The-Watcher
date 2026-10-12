package io.github.itsdropper.thewatcher.mixin.client;

import io.github.itsdropper.thewatcher.client.hosting.HostingScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiplayerScreen.class)
public abstract class MultiplayerScreenMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void theWatcher$addHostingButton(CallbackInfo ci) {
        MultiplayerScreen screen = (MultiplayerScreen) (Object) this;
        ScreenAccessor accessor = (ScreenAccessor) (Object) screen;
        MinecraftClient client = MinecraftClient.getInstance();
        accessor.theWatcher$addDrawableChild(ButtonWidget.builder(Text.literal("Watcher Host"), button ->
                client.setScreen(new HostingScreen(screen)))
                .dimensions(client.getWindow().getScaledWidth() - 110,
                        client.getWindow().getScaledHeight() - 28, 100, 20)
                .build());
    }
}
