package io.github.itsdropper.thewatcher.client;

import io.github.itsdropper.thewatcher.SurveillanceCameraBlockEntity;
import io.github.itsdropper.thewatcher.entity.WatcherEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CameraMonitorScreen extends Screen {
    private static final int SEARCH_CHUNK_RADIUS = 6;
    private static final int MAX_VISIBLE_CAMERAS = 8;
    private List<CameraStatus> cameras = List.of();

    public CameraMonitorScreen() {
        super(Text.literal("Camera Monitor"));
    }

    @Override
    protected void init() {
        refreshCameras();
        int centerX = width / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), button -> {
            refreshCameras();
        }).dimensions(centerX - 104, height - 34, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> close())
                .dimensions(centerX + 4, height - 34, 100, 20).build());
    }

    private void refreshCameras() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            cameras = List.of();
            return;
        }

        List<CameraStatus> found = new ArrayList<>();
        int centerChunkX = client.player.getChunkPos().x;
        int centerChunkZ = client.player.getChunkPos().z;
        for (int chunkX = centerChunkX - SEARCH_CHUNK_RADIUS;
             chunkX <= centerChunkX + SEARCH_CHUNK_RADIUS; chunkX++) {
            for (int chunkZ = centerChunkZ - SEARCH_CHUNK_RADIUS;
                 chunkZ <= centerChunkZ + SEARCH_CHUNK_RADIUS; chunkZ++) {
                WorldChunk chunk = client.world.getChunkManager().getWorldChunk(chunkX, chunkZ, false);
                if (chunk == null) continue;
                for (var blockEntity : chunk.getBlockEntities().values()) {
                    if (blockEntity instanceof SurveillanceCameraBlockEntity camera) {
                        found.add(readStatus(client, camera));
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(camera ->
                client.player.squaredDistanceTo(Vec3d.ofCenter(camera.pos()))));
        cameras = List.copyOf(found);
    }

    private static CameraStatus readStatus(MinecraftClient client, SurveillanceCameraBlockEntity camera) {
        BlockPos pos = camera.getPos();
        Vec3d origin = Vec3d.ofCenter(pos).add(0.0D, 0.51D, 0.0D);
        Box scanBox = new Box(pos).expand(48.0D);
        boolean detected = false;
        for (WatcherEntity watcher : client.world.getEntitiesByClass(
                WatcherEntity.class, scanBox, Entity::isAlive)) {
            if (watcher.squaredDistanceTo(origin) > 48.0D * 48.0D) continue;
            var hit = client.world.raycast(new RaycastContext(origin, watcher.getEyePos(),
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, watcher));
            if (hit.getType() == HitResult.Type.MISS) {
                detected = true;
                break;
            }
        }
        return new CameraStatus(pos.toImmutable(), detected);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        int centerX = width / 2;
        int panelWidth = Math.min(340, width - 20);
        int left = centerX - panelWidth / 2;
        int top = 22;
        int bottom = height - 44;

        context.fill(left, top, left + panelWidth, bottom, 0xE810141B);
        context.fill(left, top, left + panelWidth, top + 2, 0xFFFF3535);
        context.drawCenteredTextWithShadow(textRenderer, "CAMERA NETWORK", centerX, top + 12, 0xFFE4EAF2);
        context.drawCenteredTextWithShadow(textRenderer,
                "Loaded cameras near your current area", centerX, top + 27, 0xFF8795A7);

        if (cameras.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, "NO CAMERAS FOUND", centerX, top + 58, 0xFFFF7777);
            context.drawCenteredTextWithShadow(textRenderer,
                    "Place a camera or move closer to one.", centerX, top + 74, 0xFFB6BEC9);
        } else {
            int shown = Math.min(MAX_VISIBLE_CAMERAS, cameras.size());
            for (int i = 0; i < shown; i++) {
                CameraStatus camera = cameras.get(i);
                int rowY = top + 46 + i * 23;
                context.fill(left + 10, rowY - 3, left + panelWidth - 10, rowY + 17, 0xA0262B33);
                context.drawTextWithShadow(textRenderer, String.format(java.util.Locale.ROOT,
                        "CAM-%02d", i + 1), left + 17, rowY + 2, 0xFFB9C7D8);
                context.drawTextWithShadow(textRenderer,
                        camera.pos().getX() + ", " + camera.pos().getY() + ", " + camera.pos().getZ(),
                        left + 72, rowY + 2, 0xFF9BA8B8);
                String status = camera.detected() ? "MOTION DETECTED" : "NO CONTACT";
                int color = camera.detected() ? 0xFFFF5555 : 0xFF76D6A0;
                int statusWidth = textRenderer.getWidth(status);
                context.drawTextWithShadow(textRenderer, status,
                        left + panelWidth - 17 - statusWidth, rowY + 2, color);
            }
            if (cameras.size() > MAX_VISIBLE_CAMERAS) {
                context.drawCenteredTextWithShadow(textRenderer,
                        "+" + (cameras.size() - MAX_VISIBLE_CAMERAS) + " more loaded cameras; move closer to view them",
                        centerX, bottom - 17, 0xFF9BA8B8);
            }
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(null);
    }

    private record CameraStatus(BlockPos pos, boolean detected) {
    }
}
