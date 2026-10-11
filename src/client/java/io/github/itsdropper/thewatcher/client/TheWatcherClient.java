package io.github.itsdropper.thewatcher.client;

import io.github.itsdropper.thewatcher.TheWatcher;
import io.github.itsdropper.thewatcher.client.render.WatcherRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class TheWatcherClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(TheWatcher.WATCHER, WatcherRenderer::new);
    }
}
