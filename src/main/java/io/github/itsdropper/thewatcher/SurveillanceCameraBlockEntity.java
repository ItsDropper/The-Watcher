package io.github.itsdropper.thewatcher;

import io.github.itsdropper.thewatcher.entity.WatcherEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class SurveillanceCameraBlockEntity extends BlockEntity {
    private static final double DETECTION_RANGE = 48.0D;
    private boolean watcherVisible;
    private long lastScanTick;

    public SurveillanceCameraBlockEntity(BlockPos pos, BlockState state) {
        super(TheWatcher.CAMERA_BLOCK_ENTITY, pos, state);
    }

    public static void tick(net.minecraft.world.World world, BlockPos pos, BlockState state,
                            SurveillanceCameraBlockEntity camera) {
        if (!(world instanceof ServerWorld serverWorld) || serverWorld.getTime() - camera.lastScanTick < 10L) {
            return;
        }
        camera.lastScanTick = serverWorld.getTime();
        CameraRegistry.register(serverWorld, pos);

        Vec3d origin = Vec3d.ofCenter(pos).add(0.0D, 0.51D, 0.0D);
        Box scanBox = new Box(pos).expand(DETECTION_RANGE);
        boolean detected = serverWorld.getEntitiesByClass(WatcherEntity.class, scanBox,
                        Entity::isAlive).stream()
                .filter(watcher -> watcher.squaredDistanceTo(origin) <= DETECTION_RANGE * DETECTION_RANGE)
                .anyMatch(watcher -> hasClearView(serverWorld, origin, watcher));

        camera.watcherVisible = detected;
    }

    private static boolean hasClearView(ServerWorld world, Vec3d origin, WatcherEntity watcher) {
        var hit = world.raycast(new RaycastContext(origin, watcher.getEyePos(),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, watcher));
        return hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS;
    }

    public boolean isWatcherVisible() {
        return watcherVisible;
    }
}
