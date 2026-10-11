package io.github.itsdropper.thewatcher.client.model;

import io.github.itsdropper.thewatcher.TheWatcher;
import io.github.itsdropper.thewatcher.entity.WatcherEntity;
import net.minecraft.util.Identifier;
import software.bernie.geckolib.model.GeoModel;

public class WatcherModel extends GeoModel<WatcherEntity> {
    @Override
    public Identifier getModelResource(WatcherEntity animatable) {
        return TheWatcher.id("geo/watcher.geo.json");
    }

    @Override
    public Identifier getTextureResource(WatcherEntity animatable) {
        return TheWatcher.id("textures/entity/watcher.png");
    }

    @Override
    public Identifier getAnimationResource(WatcherEntity animatable) {
        return TheWatcher.id("animations/watcher.animation.json");
    }
}
