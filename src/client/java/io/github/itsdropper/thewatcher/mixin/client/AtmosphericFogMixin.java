package io.github.itsdropper.thewatcher.mixin.client;

import io.github.itsdropper.thewatcher.client.WatcherFogConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(net.minecraft.client.render.BackgroundRenderer.class)
public abstract class AtmosphericFogMixin {
    @ModifyArg(
            method = "applyFog",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderFogStart(F)V"),
            index = 0,
            require = 0
    )
    private static float theWatcher$softenFogStart(float original) {
        return WatcherFogConfig.adjustStart(original);
    }

    @ModifyArg(
            method = "applyFog",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderFogEnd(F)V"),
            index = 0,
            require = 0
    )
    private static float theWatcher$pullFogCloser(float original) {
        return WatcherFogConfig.adjustEnd(original);
    }

    @ModifyArgs(
            method = "applyFogColor",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderFogColor(FFF)V"),
            require = 0
    )
    private static void theWatcher$gradeFogColor(Args args) {
        if (!WatcherFogConfig.shouldApply()) return;
        args.set(0, WatcherFogConfig.blendColor((float) args.get(0), WatcherFogConfig.fogTargetRed()));
        args.set(1, WatcherFogConfig.blendColor((float) args.get(1), WatcherFogConfig.fogTargetGreen()));
        args.set(2, WatcherFogConfig.blendColor((float) args.get(2), WatcherFogConfig.fogTargetBlue()));
    }
}
