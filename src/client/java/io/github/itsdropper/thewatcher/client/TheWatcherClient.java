package io.github.itsdropper.thewatcher.client;

import io.github.itsdropper.thewatcher.TheWatcher;
import io.github.itsdropper.thewatcher.client.render.WatcherRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class TheWatcherClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(TheWatcher.WATCHER, WatcherRenderer::new);
        // Keep the common stalking policy synchronized with the actual Minecraft option,
        // even if the player never opens The Watcher's settings screen.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.options != null) {
                TheWatcher.WATCHER_RENDER_DISTANCE_CHUNKS =
                        Math.max(4, client.options.getViewDistance().getValue());
            }
        });
    }
}
