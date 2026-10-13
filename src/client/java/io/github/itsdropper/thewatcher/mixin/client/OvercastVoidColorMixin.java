package io.github.itsdropper.thewatcher.mixin.client;

import io.github.itsdropper.thewatcher.client.WatcherFogConfig;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tints the sky color used behind the terrain in the Overworld.
 */
@Mixin(ClientWorld.class)
public abstract class OvercastVoidColorMixin {
    @Inject(
            method = "getSkyColor(Lnet/minecraft/util/math/Vec3d;F)Lnet/minecraft/util/math/Vec3d;",
            at = @At("RETURN"),
            cancellable = true
    )
    private void theWatcher$grayUnloadedTerrainSky(
            Vec3d cameraPos,
            float tickDelta,
            CallbackInfoReturnable<Vec3d> cir
    ) {
        if (!WatcherFogConfig.shouldApply()) return;

        Vec3d color = cir.getReturnValue();
        double luminance = 0.2126D * color.x + 0.7152D * color.y + 0.0722D * color.z;
        double gray = Math.max(0.095D, Math.min(0.41D, luminance * 0.48D));
        cir.setReturnValue(new Vec3d(gray, gray, gray));
    }
}
