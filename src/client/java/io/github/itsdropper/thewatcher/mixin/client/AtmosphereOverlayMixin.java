package io.github.itsdropper.thewatcher.mixin.client;

import io.github.itsdropper.thewatcher.client.WatcherAtmosphereOverlay;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws atmosphere before HUD elements so the HUD stays sharp. */
@Mixin(InGameHud.class)
public abstract class AtmosphereOverlayMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void theWatcher$renderPeripheralDread(
            DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        WatcherAtmosphereOverlay.render(context);
    }
}
