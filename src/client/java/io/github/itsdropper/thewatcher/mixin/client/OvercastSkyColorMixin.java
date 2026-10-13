package io.github.itsdropper.thewatcher.mixin.client;

import io.github.itsdropper.thewatcher.client.WatcherFogConfig;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Desaturates sky-render shader colors so the horizon behind unloaded chunks
 * cannot retain vanilla's blue tint. Apply to every shader-color submission
 * during renderSky, not just one ordinal that may differ between mappings.
 */
@Mixin(WorldRenderer.class)
public abstract class OvercastSkyColorMixin {
    @ModifyArgs(
            method = "renderSky",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderColor(FFFF)V"
            ),
            require = 1
    )
    private void theWatcher$graySkyDome(Args args) {
        if (!WatcherFogConfig.shouldApply()) return;

        float red = ((Number) args.get(0)).floatValue();
        float green = ((Number) args.get(1)).floatValue();
        float blue = ((Number) args.get(2)).floatValue();
        float gray = Math.max(0.12F, Math.min(0.42F, (red + green + blue) / 3.0F * 0.58F));

        args.set(0, gray);
        args.set(1, gray);
        args.set(2, gray);
    }
}
