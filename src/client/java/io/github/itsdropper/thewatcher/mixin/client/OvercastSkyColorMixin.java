package io.github.itsdropper.thewatcher.mixin.client;

import io.github.itsdropper.thewatcher.client.WatcherFogConfig;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Grays the vanilla sky-dome shader color at the point it is submitted for
 * rendering. This avoids relying on a World.getSkyColor method that is not
 * consistently mapped/targeted in this Minecraft version.
 */
@Mixin(WorldRenderer.class)
public abstract class OvercastSkyColorMixin {
    @ModifyArgs(
            method = "renderSky",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderColor(FFFF)V",
                    ordinal = 0
            ),
            require = 0
    )
    private void theWatcher$graySkyDome(Args args) {
        if (!WatcherFogConfig.shouldApply()) return;

        float red = ((Number) args.get(0)).floatValue();
        float green = ((Number) args.get(1)).floatValue();
        float blue = ((Number) args.get(2)).floatValue();
        float gray = Math.max(0.16F, Math.min(0.48F, (red + green + blue) / 3.0F * 0.62F));

        args.set(0, gray);
        args.set(1, gray);
        args.set(2, gray);
    }
}
