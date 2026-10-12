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
        super(Text.literal("Camera Network"));
    }

    @Override
    protected void init() {
        refreshCameras();
        int centerX = width / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), button ->
                MinecraftClient.getInstance().setScreen(new CameraMonitorScreen()))
                .dimensions(centerX - 104, height - 32, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> close())
                .dimensions(centerX + 4, height - 32, 100, 20).build());

        int shown = Math.min(MAX_VISIBLE_CAMERAS, cameras.size());
        int panelWidth = Math.min(620, width - 20);
        int left = (width - panelWidth) / 2;
        for (int i = 0; i < shown; i++) {
            CameraStatus camera = cameras.get(i);
            int rowY = 82 + i * 29;
            addDrawableChild(ButtonWidget.builder(Text.literal("VIEW"), button -> openFeed(camera.pos()))
                    .dimensions(left + panelWidth - 66, rowY, 52, 20).build());
        }
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

    private void openFeed(BlockPos pos) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;
        CameraFeedScreen feed = CameraFeedScreen.open(pos, this);
        if (feed != null) client.setScreen(feed);
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
        // Draw the opaque custom UI after the vanilla screen pass. renderBackground()
        // is overridden below so the vanilla pass cannot blur the game or this UI.
        context.fill(0, 0, width, height, 0xB0000000);
        int centerX = width / 2;
        int panelWidth = Math.min(620, width - 20);
        int left = centerX - panelWidth / 2;
        int top = 22;
        int bottom = height - 40;

        context.fill(left, top, left + panelWidth, bottom, 0xFF10141B);
        context.fill(left, top, left + panelWidth, top + 2, 0xFFFF3535);
        context.drawText(textRenderer, "CAMERA NETWORK", centerX - textRenderer.getWidth("CAMERA NETWORK") / 2, top + 12, 0xFFE4EAF2, false);
        String subtitle = "Nearby loaded cameras • select VIEW for a live feed";
        context.drawText(textRenderer, subtitle, centerX - textRenderer.getWidth(subtitle) / 2, top + 28, 0xFF9BA8B8, false);

        if (cameras.isEmpty()) {
            String emptyTitle = "NO CAMERAS FOUND";
            context.drawText(textRenderer, emptyTitle, centerX - textRenderer.getWidth(emptyTitle) / 2, top + 60, 0xFFFF7777, false);
            String emptyHint = "Place a camera or move closer to one.";
            context.drawText(textRenderer, emptyHint, centerX - textRenderer.getWidth(emptyHint) / 2, top + 77, 0xFFB6BEC9, false);
        } else {
            int shown = Math.min(MAX_VISIBLE_CAMERAS, cameras.size());
            for (int i = 0; i < shown; i++) {
                CameraStatus camera = cameras.get(i);
                int rowY = 82 + i * 29;
                context.fill(left + 10, rowY - 4, left + panelWidth - 10, rowY + 23, 0xFF222831);
                context.drawText(textRenderer, String.format(java.util.Locale.ROOT, "CAM-%02d", i + 1),
                        left + 18, rowY + 3, 0xFFE4EAF2, false);
                context.drawText(textRenderer,
                        camera.pos().getX() + ", " + camera.pos().getY() + ", " + camera.pos().getZ(),
                        left + 94, rowY + 3, 0xFF9BA8B8, false);
                String status = camera.detected() ? "WATCHER IN VIEW" : "NO CONTACT";
                int color = camera.detected() ? 0xFFFF5555 : 0xFF76D6A0;
                context.drawText(textRenderer, status,
                        left + panelWidth - 160 - textRenderer.getWidth(status), rowY + 3, color, false);
            }
            if (cameras.size() > MAX_VISIBLE_CAMERAS) {
                String moreCameras = "+" + (cameras.size() - MAX_VISIBLE_CAMERAS)
                        + " more loaded cameras; move closer to view them";
                context.drawText(textRenderer, moreCameras,
                        centerX - textRenderer.getWidth(moreCameras) / 2, bottom - 17, 0xFF9BA8B8, false);
            }
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // Deliberately no-op: vanilla menu background rendering applies blur.
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(null);
    }

    private record CameraStatus(BlockPos pos, boolean detected) {
    }
}
