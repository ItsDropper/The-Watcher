package io.github.itsdropper.thewatcher.client;

import io.github.itsdropper.thewatcher.CameraFeedClosePayload;
import io.github.itsdropper.thewatcher.CameraFeedOpenPayload;
import io.github.itsdropper.thewatcher.SurveillanceCameraBlock;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.util.InputUtil;

public final class CameraFeedScreen extends Screen {
    private final BlockPos cameraPos;
    private final Screen networkScreen;
    private final Entity previousCameraEntity;
    private final Entity viewEntity;
    private final World cameraWorld;
    private boolean restored;
    private boolean cameraOrientationSet;
    private boolean rotateLeft;
    private boolean rotateRight;
    private boolean tiltUp;
    private boolean tiltDown;
    private double lastMouseX = Double.NaN;
    private double lastMouseY = Double.NaN;
    private boolean draggingLook;
    private final int originalFov = MinecraftClient.getInstance().options.getFov().getValue();
    private int zoomFov = 30;
    private boolean zoomTransitionActive;

    private CameraFeedScreen(BlockPos cameraPos, Screen networkScreen,
                             Entity previousCameraEntity, Entity viewEntity, World cameraWorld) {
        super(Text.literal("Camera Feed"));
        this.cameraPos = cameraPos.toImmutable();
        this.networkScreen = networkScreen;
        this.previousCameraEntity = previousCameraEntity;
        this.viewEntity = viewEntity;
        this.cameraWorld = cameraWorld;
    }

    public static CameraFeedScreen open(BlockPos pos, Screen networkScreen) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return null;

        var view = EntityType.ARMOR_STAND.create(client.world);
        if (view == null) return null;

        view.setPosition(pos.getX() + 0.5D, pos.getY() - 0.7D, pos.getZ() + 0.5D);
        view.setNoGravity(true);
        view.setInvisible(true);
        Direction facing = client.world.getBlockState(pos).contains(SurveillanceCameraBlock.FACING)
                ? client.world.getBlockState(pos).get(SurveillanceCameraBlock.FACING)
                : Direction.NORTH;
        view.setYaw(facing.asRotation());
        view.setPitch(0.0F);

        Entity previous = client.getCameraEntity();
        view.setId(-2_147_000_001);
        client.world.addEntity(view);
        ClientPlayNetworking.send(new CameraFeedOpenPayload(pos));
        CameraFeedScreen screen = new CameraFeedScreen(pos, networkScreen, previous, view, client.world);
        client.setCameraEntity(view);
        return screen;
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("Return to network"), button -> close())
                .dimensions(width / 2 - 65, height - 30, 130, 20).build());
    }

    @Override
    public void tick() {
        super.tick();
        if (client == null || client.getWindow() == null) return;
        long window = client.getWindow().getHandle();
        boolean holdingZoom = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_C);
        if (holdingZoom) zoomTransitionActive = true;
        if (zoomTransitionActive) {
            int targetFov = holdingZoom ? zoomFov : originalFov;
            int currentFov = client.options.getFov().getValue();
            int nextFov = Math.round(currentFov + (targetFov - currentFov) * 0.30F);
            // FOV is stored as an integer option, so guarantee progress on small final steps.
            if (nextFov == currentFov && currentFov != targetFov) {
                nextFov += Integer.compare(targetFov, currentFov);
            }
            client.options.getFov().setValue(nextFov);
            if (!holdingZoom && nextFov == originalFov) zoomTransitionActive = false;
        }
        if (!cameraOrientationSet && client.world != null) {
            var chunk = client.world.getChunkManager().getWorldChunk(
                    cameraPos.getX() >> 4, cameraPos.getZ() >> 4, false);
            if (chunk != null && client.world.getBlockState(cameraPos).isOf(io.github.itsdropper.thewatcher.TheWatcher.SURVEILLANCE_CAMERA)) {
                Direction facing = client.world.getBlockState(cameraPos).contains(SurveillanceCameraBlock.FACING)
                        ? client.world.getBlockState(cameraPos).get(SurveillanceCameraBlock.FACING) : Direction.NORTH;
                viewEntity.setYaw(facing.asRotation());
                viewEntity.setPitch(0.0F);
                cameraOrientationSet = true;
            }
        }
        float yaw = viewEntity.getYaw();
        float pitch = viewEntity.getPitch();
        float turn = 2.4F;
        if (rotateLeft || InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_A)) yaw -= turn;
        if (rotateRight || InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_D)) yaw += turn;
        if (tiltUp || InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_W)) pitch -= 1.8F;
        if (tiltDown || InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_S)) pitch += 1.8F;
        setViewRotation(yaw, pitch);
    }

    private void setViewRotation(float yaw, float pitch) {
        float clampedPitch = MathHelper.clamp(pitch, -89.0F, 89.0F);
        // Use public setters (Entity#setRotation is protected in this Minecraft version).
        // Synchronize previous values so camera interpolation does not introduce jitter.
        viewEntity.setYaw(yaw);
        viewEntity.setPitch(clampedPitch);
        viewEntity.setBodyYaw(yaw);
        viewEntity.setHeadYaw(yaw);
        viewEntity.prevYaw = yaw;
        viewEntity.prevPitch = clampedPitch;
        viewEntity.prevBodyYaw = yaw;
        viewEntity.prevHeadYaw = yaw;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (draggingLook && !Double.isNaN(lastMouseX) && !Double.isNaN(lastMouseY)) {
            double deltaX = mouseX - lastMouseX;
            double deltaY = mouseY - lastMouseY;
            setViewRotation(
                    viewEntity.getYaw() - (float) deltaX * 0.45F,
                    viewEntity.getPitch() - (float) deltaY * 0.45F);
        }
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (draggingLook && button == GLFW.GLFW_MOUSE_BUTTON_1) {
            setViewRotation(
                    viewEntity.getYaw() - (float) deltaX * 0.45F,
                    viewEntity.getPitch() - (float) deltaY * 0.45F);
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (client != null && client.getWindow() != null
                && InputUtil.isKeyPressed(client.getWindow().getHandle(), GLFW.GLFW_KEY_C)) {
            zoomFov = MathHelper.clamp(zoomFov - (int) Math.signum(verticalAmount) * 4, 10, 30);
            zoomTransitionActive = true;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_1) {
            draggingLook = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_1) {
            draggingLook = false;
            lastMouseX = Double.NaN;
            lastMouseY = Double.NaN;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_A) { rotateLeft = true; return true; }
        if (keyCode == GLFW.GLFW_KEY_D) { rotateRight = true; return true; }
        if (keyCode == GLFW.GLFW_KEY_W) { tiltUp = true; return true; }
        if (keyCode == GLFW.GLFW_KEY_S) { tiltDown = true; return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_A) { rotateLeft = false; return true; }
        if (keyCode == GLFW.GLFW_KEY_D) { rotateRight = false; return true; }
        if (keyCode == GLFW.GLFW_KEY_W) { tiltUp = false; return true; }
        if (keyCode == GLFW.GLFW_KEY_S) { tiltDown = false; return true; }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, 42, 0xFF0A0D12);
        context.fill(0, height - 40, width, height, 0xFF0A0D12);
        context.drawText(textRenderer, "LIVE FEED  /  CAM " + cameraPos.getX() + " "
                + cameraPos.getY() + " " + cameraPos.getZ(), 12, 10, 0xFFFF5555, false);
        context.drawText(textRenderer, "W/S: tilt   A/D: rotate   Drag: look   Hold C: zoom   C + scroll: more zoom   ESC: return",
                12, 25, 0xFFE4EAF2, false);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // Keep the camera image sharp: no vanilla menu blur.
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
        if (client != null) client.options.getFov().setValue(originalFov);
        zoomTransitionActive = false;
        ClientPlayNetworking.send(new CameraFeedClosePayload());
        if (client != null && previousCameraEntity != null) {
            client.setCameraEntity(previousCameraEntity);
        }
        if (cameraWorld != null && cameraWorld.getEntityById(viewEntity.getId()) == viewEntity) {
            viewEntity.remove(Entity.RemovalReason.DISCARDED);
        }
    }
}
