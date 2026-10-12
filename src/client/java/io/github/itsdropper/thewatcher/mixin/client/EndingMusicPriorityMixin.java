package io.github.itsdropper.thewatcher.mixin.client;

import io.github.itsdropper.thewatcher.TheWatcher;
import io.github.itsdropper.thewatcher.client.TheWatcherClient;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.sound.SoundCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundManager.class)
public abstract class EndingMusicPriorityMixin {
    @Inject(method = "play(Lnet/minecraft/client/sound/SoundInstance;)V", at = @At("HEAD"), cancellable = true)
    private void theWatcher$suppressOtherMusic(SoundInstance sound, CallbackInfo ci) {
        if (TheWatcherClient.isEndingMusicActive()
                && sound.getCategory() == SoundCategory.MUSIC
                && !sound.getId().equals(TheWatcher.id("ending_theme"))) {
            ci.cancel();
        }
    }
}
