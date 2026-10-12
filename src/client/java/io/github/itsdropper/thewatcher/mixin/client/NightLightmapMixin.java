package io.github.itsdropper.thewatcher.mixin.client;

import io.github.itsdropper.thewatcher.TheWatcher;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightmapTextureManager.class)
public abstract class NightLightmapMixin {
    @Shadow @Final private NativeImage image;

    @Inject(method = "update", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/texture/NativeImageBackedTexture;upload()V",
            shift = At.Shift.BEFORE))
    private void thewatcher$darkenNightLightmap(float delta, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!TheWatcher.NIGHT_DARKNESS_ENABLED || client.world == null
                || client.world.getRegistryKey() != World.OVERWORLD) {
            return;
        }

        long time = Math.floorMod(client.world.getTimeOfDay(), 24_000L);
        if (time < 13_000L || time >= 23_000L) {
            return;
        }

        // X is the block-light axis in vanilla's 16x16 lightmap. Preserve strong
        // block light so torches remain useful while unlit night terrain falls much darker.
        for (int blockLight = 0; blockLight < 16; blockLight++) {
            float multiplier;
            if (blockLight == 0) {
                multiplier = 0.20F;
            } else if (blockLight <= 3) {
                multiplier = 0.30F + blockLight * 0.055F;
            } else if (blockLight <= 8) {
                multiplier = 0.48F + (blockLight - 3) * 0.075F;
            } else {
                multiplier = Math.min(0.94F, 0.855F + (blockLight - 8) * 0.017F);
            }

            for (int skyLight = 0; skyLight < 16; skyLight++) {
                int argb = image.getColorArgb(blockLight, skyLight);
                int alpha = (argb >>> 24) & 0xFF;
                int red = (int) (((argb >>> 16) & 0xFF) * multiplier);
                int green = (int) (((argb >>> 8) & 0xFF) * multiplier);
                int blue = (int) ((argb & 0xFF) * multiplier);
                image.setColorArgb(blockLight, skyLight,
                        (alpha << 24) | (red << 16) | (green << 8) | blue);
            }
        }
    }
}
