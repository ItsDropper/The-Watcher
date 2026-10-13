package io.github.itsdropper.thewatcher.mixin.client;

import io.github.itsdropper.thewatcher.client.WatcherFogConfig;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tints the actual Overworld sky color returned to the renderer. The blue
 * visible past the loaded terrain comes from this color, not the framebuffer
 * clear color, so changing GameRenderer.clearColor does not affect it.
 */
@Mixin(ClientWorld.class)
public abstract class OvercastVoidColorMixin {
    @Inject(
            method = "getSkyColor(Lnet/minecraft/util/math/Vec3d;F)I",
            at = @At("RETURN"),
            cancellable = true
    )
    private void theWatcher$grayUnloadedTerrainSky(
            net.minecraft.util.math.Vec3d cameraPos,
            float tickDelta,
            CallbackInfoReturnable<Integer> cir
    ) {
        if (!WatcherFogConfig.shouldApply()) return;

        int color = cir.getReturnValue();
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;

        // Luminance-weighted grayscale, darkened to fit the overcast palette.
        int gray = Math.max(24, Math.min(104,
                Math.round((0.2126F * red + 0.7152F * green + 0.0722F * blue) * 0.48F)));
        cir.setReturnValue((color & 0xFF000000) | (gray << 16) | (gray << 8) | gray);
    }
}
