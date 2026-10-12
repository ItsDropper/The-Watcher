package io.github.itsdropper.thewatcher.client;

import io.github.itsdropper.thewatcher.SurveillanceCameraBlockEntity;
import io.github.itsdropper.thewatcher.entity.WatcherEntity;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.World;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public final class CameraAlertHud {
    private static int alertTicks;
    private static int scanCooldown;
    private static boolean detectedLastScan;
    private static boolean signalLost;
    private static int rareAlertTicks;
    private static int rareAlertCooldown;
    private static String rareAlertText = "UNKNOWN MOVEMENT";

    private CameraAlertHud() {
    }

    public static void register() {
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null) {
                alertTicks = 0;
                detectedLastScan = false;
                rareAlertTicks = 0;
                rareAlertCooldown = 0;
                return;
            }

            if (rareAlertCooldown > 0) rareAlertCooldown--;
            if (rareAlertTicks > 0) rareAlertTicks--;

            if (scanCooldown-- <= 0) {
                scanCooldown = 10;
                boolean detected = cameraSeesWatcher(client);
                long time = Math.floorMod(client.world.getTimeOfDay(), 24_000L);
                boolean night = time >= 13_000L && time < 23_000L;
                if (!detected && !detectedLastScan && night
                        && client.world.getRegistryKey() == World.OVERWORLD
                        && rareAlertCooldown <= 0 && client.world.random.nextInt(1800) == 0) {
                    String[] alerts = {
                            "UNKNOWN MOVEMENT",
                            "FRAME MISMATCH",
                            "SIGNAL INTEGRITY LOST"
                    };
                    rareAlertText = alerts[client.world.random.nextInt(alerts.length)];
                    rareAlertTicks = 60;
                    rareAlertCooldown = 12_000;
                }
                if (detected && !detectedLastScan) {
                    signalLost = client.world.random.nextInt(5) == 0;
                }
                if (detected || detectedLastScan) {
                    alertTicks = 70;
                }
                detectedLastScan = detected;
            }
            if (alertTicks <= 0 && rareAlertTicks <= 0) {
                return;
            }
            if (alertTicks > 0) alertTicks--;

            int x = drawContext.getScaledWindowWidth() - 174;
            int y = 12;
            drawContext.fill(x, y, x + 162, y + 39, 0xC0101118);
            drawContext.fill(x, y, x + 162, y + 2, 0xFFFF3535);
            drawContext.fill(x, y + 2, x + 1, y + 39, 0xFFFF3535);
            drawContext.drawText(client.textRenderer, Text.literal("CAMERA NETWORK"),
                    x + 8, y + 7, 0xFFB9C7D8, false);
            String status = rareAlertTicks > 0
                    ? rareAlertText
                    : (signalLost ? "SIGNAL LOST" : "MOVEMENT DETECTED");
            drawContext.drawText(client.textRenderer, Text.literal(status),
                    x + 8, y + 21, 0xFFFF5555, true);
        });
    }

    private static boolean cameraSeesWatcher(MinecraftClient client) {
        int centerChunkX = client.player.getChunkPos().x;
        int centerChunkZ = client.player.getChunkPos().z;
        for (int chunkX = centerChunkX - 6; chunkX <= centerChunkX + 6; chunkX++) {
            for (int chunkZ = centerChunkZ - 6; chunkZ <= centerChunkZ + 6; chunkZ++) {
                WorldChunk chunk = client.world.getChunkManager().getWorldChunk(chunkX, chunkZ, false);
                if (chunk == null) {
                    continue;
                }
                for (var blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof SurveillanceCameraBlockEntity camera)) {
                        continue;
                    }
                    Vec3d origin = Vec3d.ofCenter(camera.getPos()).add(0.0D, 0.51D, 0.0D);
                    Box scanBox = new Box(camera.getPos()).expand(48.0D);
                    for (WatcherEntity watcher : client.world.getEntitiesByClass(
                            WatcherEntity.class, scanBox, Entity::isAlive)) {
                        if (watcher.squaredDistanceTo(origin) > 48.0D * 48.0D) {
                            continue;
                        }
                        var hit = client.world.raycast(new RaycastContext(origin, watcher.getEyePos(),
                                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, watcher));
                        if (hit.getType() == HitResult.Type.MISS) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
