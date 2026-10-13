package io.github.itsdropper.thewatcher.mixin.client;

import io.github.itsdropper.thewatcher.client.WatcherFogConfig;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Changes the OpenGL framebuffer clear color itself. This is the void/background
 * color visible through gaps beyond loaded terrain, not the sky dome color.
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

        // Use a dark neutral gray rather than the vanilla blue.
        float luminance = 0.2126F * red + 0.7152F * green + 0.0722F * blue;
        float gray = Math.max(0.075F, Math.min(0.30F, luminance * 0.48F));
        args.set(0, gray);
        args.set(1, gray);
        args.set(2, gray);
    }
}
