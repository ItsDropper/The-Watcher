package io.github.itsdropper.thewatcher.mixin.client;

import io.github.itsdropper.thewatcher.client.CameraFeedScreen;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class CameraZoomMixin {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void thewatcher$applyCameraZoom(Camera camera, float tickDelta, boolean changingFov,
                                            CallbackInfoReturnable<Double> cir) {
        double multiplier = CameraFeedScreen.getActiveZoomMultiplier();
        if (multiplier != 1.0D) {
            cir.setReturnValue(cir.getReturnValue() * multiplier);
        }
    }
}
