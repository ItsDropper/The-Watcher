package io.github.itsdropper.thewatcher.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.itsdropper.thewatcher.client.WatcherFogConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Replaces only the vanilla sun texture while The Watcher's Overworld atmosphere is active.
 * The moon, stars, and sky dome are left to vanilla rendering.
 */
@Mixin(WorldRenderer.class)
public abstract class HideSunMixin {
    private static Identifier theWatcher$transparentSunTexture;

    @Redirect(
            method = "renderSky",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderTexture(ILnet/minecraft/util/Identifier;)V"
            )
    )
    private void theWatcher$hideSunTexture(int textureUnit, Identifier texture) {
        if (WatcherFogConfig.shouldHideSun()
                && texture.getPath().endsWith("/sun.png")) {
            RenderSystem.setShaderTexture(textureUnit, theWatcher$getTransparentSunTexture());
        } else {
            RenderSystem.setShaderTexture(textureUnit, texture);
        }
    }

    private static Identifier theWatcher$getTransparentSunTexture() {
        if (theWatcher$transparentSunTexture == null) {
            NativeImage image = new NativeImage(1, 1, true);
            image.setColor(0, 0, 0x00000000);
            NativeImageBackedTexture transparentTexture = new NativeImageBackedTexture(image);
            theWatcher$transparentSunTexture = MinecraftClient.getInstance()
                    .getTextureManager()
                    .registerDynamicTexture("the_watcher_transparent_sun", transparentTexture);
        }
        return theWatcher$transparentSunTexture;
    }
}
