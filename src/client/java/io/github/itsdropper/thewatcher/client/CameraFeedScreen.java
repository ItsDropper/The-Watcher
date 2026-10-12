package io.github.itsdropper.thewatcher.client;

import io.github.itsdropper.thewatcher.SurveillanceCameraBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public final class CameraFeedScreen extends Screen {
    private final BlockPos cameraPos;
    private final Screen networkScreen;
    private final Entity previousCameraEntity;
    private final Entity viewEntity;
    private boolean restored;

    private CameraFeedScreen(BlockPos cameraPos, Screen networkScreen,
                             Entity previousCameraEntity, Entity viewEntity) {
        super(Text.literal("Camera Feed"));
        this.cameraPos = cameraPos.toImmutable();
        this.networkScreen = networkScreen;
        this.previousCameraEntity = previousCameraEntity;
        this.viewEntity = viewEntity;
    }

    public static CameraFeedScreen open(BlockPos pos, Screen networkScreen) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return null;

        var view = EntityType.ARMOR_STAND.create(client.world);
        if (view == null) return null;

        // Armor-stand eye height places the virtual viewpoint at the camera's lens.
        view.setPosition(pos.getX() + 0.5D, pos.getY() - 0.7D, pos.getZ() + 0.5D);
        view.setNoGravity(true);
        view.setInvisible(true);
        Direction facing = client.world.getBlockState(pos).contains(SurveillanceCameraBlock.FACING)
                ? client.world.getBlockState(pos).get(SurveillanceCameraBlock.FACING)
                : Direction.NORTH;
        view.setYaw(facing.asRotation());
        view.setPitch(0.0F);

        Entity previous = client.getCameraEntity();
        CameraFeedScreen screen = new CameraFeedScreen(pos, networkScreen, previous, view);
        client.setCameraEntity(view);
        return screen;
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("Return to network"), button -> close())
                .dimensions(width / 2 - 65, height - 30, 130, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // The 3D world is rendered by Minecraft from the virtual camera entity behind this
        // overlay. Avoid renderBackground(), which blurs the live view in vanilla screens.
        context.fill(0, 0, width, 42, 0xD90A0D12);
        context.fill(0, height - 40, width, height, 0xD90A0D12);
        context.drawText(textRenderer, "LIVE FEED  /  CAM " + cameraPos.getX() + " "
                + cameraPos.getY() + " " + cameraPos.getZ(), 12, 10, 0xFFFF5555, false);
        context.drawText(textRenderer, "W/S: tilt   A/D: pan   ESC: return to network",
                12, 25, 0xFFE4EAF2, false);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        float yaw = viewEntity.getYaw();
        float pitch = viewEntity.getPitch();
        switch (keyCode) {
            case 87 -> pitch = Math.max(-85.0F, pitch - 6.0F); // W
            case 83 -> pitch = Math.min(85.0F, pitch + 6.0F); // S
            case 65 -> yaw -= 8.0F; // A
            case 68 -> yaw += 8.0F; // D
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        viewEntity.setYaw(yaw);
        viewEntity.setPitch(pitch);
        viewEntity.setHeadYaw(yaw);
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        restorePlayerView();
        if (client != null) client.setScreen(networkScreen);
    }

    @Override
    public void removed() {
        restorePlayerView();
        super.removed();
    }

    private void restorePlayerView() {
        if (restored) return;
        restored = true;
        if (client != null && previousCameraEntity != null) {
            client.setCameraEntity(previousCameraEntity);
        }
    }
}
