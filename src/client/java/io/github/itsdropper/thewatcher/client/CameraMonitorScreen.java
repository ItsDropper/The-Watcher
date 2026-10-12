package io.github.itsdropper.thewatcher.client;

import io.github.itsdropper.thewatcher.CameraListRequestPayload;
import io.github.itsdropper.thewatcher.SurveillanceCameraBlock;
import io.github.itsdropper.thewatcher.SurveillanceCameraBlockEntity;
import io.github.itsdropper.thewatcher.entity.WatcherEntity;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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
    private static final int MAX_VISIBLE_CAMERAS = 8;
    private static List<BlockPos> serverCameras = List.of();
    private List<CameraStatus> cameras = List.of();
    private final List<ButtonWidget> viewButtons = new ArrayList<>();

    public CameraMonitorScreen() {
        super(Text.literal("Camera Network"));
    }

    public static void receiveCameras(List<BlockPos> positions) {
        serverCameras = positions.stream().map(BlockPos::toImmutable).toList();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen instanceof CameraMonitorScreen screen) {
            screen.refreshCameras();
            screen.updateViewButtons();
        }
    }

    @Override
    protected void init() {
        ClientPlayNetworking.send(new CameraListRequestPayload());
        refreshCameras();
        int centerX = width / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), button -> {
            ClientPlayNetworking.send(CameraNetwork.REQUEST, PacketByteBufs.create());
        }).dimensions(centerX - 104, height - 32, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> close())
                .dimensions(centerX + 4, height - 32, 100, 20).build());

        viewButtons.clear();
        int panelWidth = Math.min(620, width - 20);
        int left = (width - panelWidth) / 2;
        for (int i = 0; i < MAX_VISIBLE_CAMERAS; i++) {
            final int index = i;
            ButtonWidget view = ButtonWidget.builder(Text.literal("VIEW"), button -> openFeedAt(index))
                    .dimensions(left + panelWidth - 66, 82 + i * 29, 52, 20).build();
            view.active = i < cameras.size();
            viewButtons.add(view);
            addDrawableChild(view);
        }
        updateViewButtons();
    }

    private void refreshCameras() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            cameras = List.of();
            return;
        }

        List<CameraStatus> found = new ArrayList<>();
        for (BlockPos pos : serverCameras) {
            WorldChunk chunk = client.world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4, false);
            if (chunk == null || !(chunk.getBlockEntity(pos) instanceof SurveillanceCameraBlockEntity camera)) {
                found.add(new CameraStatus(pos, false, false));
            } else {
                found.add(readStatus(client, camera));
            }
        }
        found.sort(Comparator.comparingDouble(camera ->
                client.player.squaredDistanceTo(Vec3d.ofCenter(camera.pos()))));
        cameras = List.copyOf(found);
    }

    private void updateViewButtons() {
        for (int i = 0; i < viewButtons.size(); i++) {
            viewButtons.get(i).active = i < cameras.size();
        }
    }

    private void openFeedAt(int index) {
        if (index < 0 || index >= cameras.size()) return;
        openFeed(cameras.get(index).pos());
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
        return new CameraStatus(pos.toImmutable(), detected, true);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xB0000000);
        int centerX = width / 2;
        int panelWidth = Math.min(620, width - 20);
        int left = centerX - panelWidth / 2;
        int top = 22;
        int bottom = height - 40;

        context.fill(left, top, left + panelWidth, bottom, 0xFF10141B);
        context.fill(left, top, left + panelWidth, top + 2, 0xFFFF3535);
        context.drawText(textRenderer, "CAMERA NETWORK", centerX - textRenderer.getWidth("CAMERA NETWORK") / 2, top + 12, 0xFFE4EAF2, false);
        String subtitle = "World camera registry • offline cameras remain listed";
        context.drawText(textRenderer, subtitle, centerX - textRenderer.getWidth(subtitle) / 2, top + 28, 0xFF9BA8B8, false);

        if (cameras.isEmpty()) {
            String emptyTitle = serverCameras.isEmpty() ? "NO CAMERAS REGISTERED" : "SYNCING CAMERA NETWORK";
            context.drawText(textRenderer, emptyTitle, centerX - textRenderer.getWidth(emptyTitle) / 2, top + 60, 0xFFFF7777, false);
            String emptyHint = serverCameras.isEmpty()
                    ? "Place a camera, then reopen or refresh this screen."
                    : "Waiting for camera data.";
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
                String status = !camera.loaded() ? "OFFLINE" : camera.detected() ? "WATCHER IN VIEW" : "NO CONTACT";
                int color = !camera.loaded() ? 0xFF8994A3 : camera.detected() ? 0xFFFF5555 : 0xFF76D6A0;
                context.drawText(textRenderer, status,
                        left + panelWidth - 160 - textRenderer.getWidth(status), rowY + 3, color, false);
            }
            if (cameras.size() > MAX_VISIBLE_CAMERAS) {
                String moreCameras = "+" + (cameras.size() - MAX_VISIBLE_CAMERAS) + " more cameras in registry";
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

    private record CameraStatus(BlockPos pos, boolean detected, boolean loaded) {
    }
}
