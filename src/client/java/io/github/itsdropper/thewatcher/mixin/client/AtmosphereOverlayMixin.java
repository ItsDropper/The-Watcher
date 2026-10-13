package io.github.itsdropper.thewatcher.mixin.client;

import io.github.itsdropper.thewatcher.client.WatcherAtmosphereOverlay;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds The Watcher's atmospheric peripheral vignette after the vanilla HUD. */
@Mixin(InGameHud.class)
public abstract class AtmosphereOverlayMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void theWatcher$renderPeripheralDread(
            DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        WatcherAtmosphereOverlay.render(context);
    }
}
