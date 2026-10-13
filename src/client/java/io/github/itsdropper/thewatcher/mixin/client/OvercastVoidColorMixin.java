package io.github.itsdropper.thewatcher.mixin.client;

import io.github.itsdropper.thewatcher.client.WatcherFogConfig;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Grays the framebuffer clear color behind the rendered terrain. This is
 * separate from the sky dome: it colors the blue void visible through gaps
 * at the edge of loaded chunks.
 */
@Mixin(GameRenderer.class)
public abstract class OvercastVoidColorMixin {
    @ModifyArgs(
            method = "renderWorld",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;clearColor(FFFF)V"
            ),
            require = 0
    )
    private void theWatcher$grayUnloadedTerrainBackground(Args args) {
        if (!WatcherFogConfig.shouldApply()) return;

        float red = ((Number) args.get(0)).floatValue();
        float green = ((Number) args.get(1)).floatValue();
        float blue = ((Number) args.get(2)).floatValue();

        float gray = Math.max(0.12F, Math.min(0.42F,
                (red + green + blue) / 3.0F * 0.68F));
        args.set(0, gray);
        args.set(1, gray);
        args.set(2, gray);
    }
}
