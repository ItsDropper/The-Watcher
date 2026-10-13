package io.github.itsdropper.thewatcher.mixin.client;

import io.github.itsdropper.thewatcher.client.WatcherFogConfig;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Desaturates the sky color at its source. WorldRenderer does not own the
 * vanilla sky-color calculation, so injecting there can compile while never
 * changing the color actually used to draw the sky.
 */
@Mixin(World.class)
public abstract class OvercastSkyColorMixin {
    @Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true, require = 0)
    private void theWatcher$grayTheSky(
            BlockPos cameraPos, float tickDelta, CallbackInfoReturnable<Vec3d> cir) {
        if (!WatcherFogConfig.shouldApply()) return;
        Vec3d original = cir.getReturnValue();
        if (original == null) return;

        // Neutral, overcast gray; avoid painting anything over the player's view.
        double brightness = (original.x + original.y + original.z) / 3.0D;
        double gray = Math.max(0.075D, Math.min(0.42D, brightness * 0.58D));
        cir.setReturnValue(new Vec3d(gray, gray, gray));
    }
}
