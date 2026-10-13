package io.github.itsdropper.thewatcher.mixin.client;

import io.github.itsdropper.thewatcher.client.WatcherFogConfig;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Removes vanilla cloud geometry while the mod's overcast sky treatment is active. */
@Mixin(WorldRenderer.class)
public abstract class OvercastSkyMixin {
    @Inject(
            method = "renderClouds(Lnet/minecraft/client/util/math/MatrixStack;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FDDD)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void theWatcher$removeVanillaClouds(
            MatrixStack matrices,
            Matrix4f positionMatrix,
            Matrix4f projectionMatrix,
            float tickDelta,
            double cameraX,
            double cameraY,
            double cameraZ,
            CallbackInfo ci
    ) {
        if (WatcherFogConfig.shouldApply()) ci.cancel();
    }
}
