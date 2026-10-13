package io.github.itsdropper.thewatcher.mixin.client;

import io.github.itsdropper.thewatcher.client.WatcherFogConfig;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Desaturates the actual world sky color instead of painting a screen-space overlay. */
@Mixin(WorldRenderer.class)
public abstract class OvercastSkyColorMixin {
    @Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true, require = 0)
    private void theWatcher$grayTheSky(
            Camera camera, float tickDelta, CallbackInfoReturnable<Vec3d> cir) {
        if (!WatcherFogConfig.shouldApply()) return;
        Vec3d original = cir.getReturnValue();
        if (original == null) return;

        double brightness = (original.x + original.y + original.z) / 3.0D;
        // Desaturate heavily and slightly darken: gray overcast rather than blue daylight.
        double gray = Math.max(0.075D, Math.min(0.48D, brightness * 0.68D));
        cir.setReturnValue(new Vec3d(gray, gray, gray));
    }
}
