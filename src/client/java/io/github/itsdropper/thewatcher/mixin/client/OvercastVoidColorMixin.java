package io.github.itsdropper.thewatcher.mixin.client;

import io.github.itsdropper.thewatcher.client.WatcherFogConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Matches the framebuffer void to the same neutral-gray transform used by
 * the overcast sky shader, so gaps beyond loaded terrain blend in more closely.
 */
@Mixin(RenderSystem.class)
public abstract class OvercastVoidColorMixin {
    @ModifyArgs(
            method = "clearColor(FFFF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/GlStateManager;_clearColor(FFFF)V"
            ),
            require = 1
    )
    private static void theWatcher$grayVoidClearColor(Args args) {
        if (!WatcherFogConfig.shouldApply()) return;

        float red = ((Number) args.get(0)).floatValue();
        float green = ((Number) args.get(1)).floatValue();
        float blue = ((Number) args.get(2)).floatValue();

        // Keep the void's grayscale mapping identical to OvercastSkyColorMixin.
        float gray = Math.max(0.12F, Math.min(0.42F,
                (red + green + blue) / 3.0F * 0.58F));
        args.set(0, gray);
        args.set(1, gray);
        args.set(2, gray);
    }
}
