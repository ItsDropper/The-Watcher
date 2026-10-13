package io.github.itsdropper.thewatcher.mixin.client;

import io.github.itsdropper.thewatcher.client.WatcherFogConfig;
import net.minecraft.client.render.SkyRendering;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppress only the sun quad. The sky, stars, and moon continue to render.
 */
@Mixin(SkyRendering.class)
public abstract class HideSunMixin {
    @Inject(method = "renderSun", at = @At("HEAD"), cancellable = true, require = 0)
    private void theWatcher$hideSun(CallbackInfo ci) {
        if (WatcherFogConfig.shouldHideSun()) {
            ci.cancel();
        }
    }
}
