package io.github.itsdropper.thewatcher.mixin.client;

import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiplayerScreen.class)
public abstract class MultiplayerScreenMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void theWatcher$addHostingButton(CallbackInfo ci) {
        // Hosting menu temporarily disabled while the public relay is unavailable.
        // Intentionally leave the multiplayer screen unchanged for compatibility with Essential and other mods.
    }
}
