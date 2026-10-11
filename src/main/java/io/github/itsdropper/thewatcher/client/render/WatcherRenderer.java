package io.github.itsdropper.thewatcher.client.render;

import io.github.itsdropper.thewatcher.client.model.WatcherModel;
import io.github.itsdropper.thewatcher.entity.WatcherEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class WatcherRenderer extends GeoEntityRenderer<WatcherEntity> {
    public WatcherRenderer(EntityRendererFactory.Context context) {
        super(context, new WatcherModel());
        this.shadowRadius = 0.4F;
    }
}
