package io.github.itsdropper.thewatcher;

import net.minecraft.block.BlockRenderType;
import net.minecraft.block.Blocks;
import net.minecraft.sound.SoundEvents;
import net.minecraft.server.world.ServerWorld;
import io.github.itsdropper.thewatcher.entity.WatcherEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.Mirror;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.BlockRotation;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;

public class SurveillanceCameraBlock extends BlockWithEntity {
    public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;

    private static final VoxelShape BODY = VoxelShapes.union(
            createCuboidShape(2, 5, 3, 14, 13, 13),
            createCuboidShape(5, 2, 6, 11, 6, 10),
            createCuboidShape(6, 0, 7, 10, 3, 9),
            createCuboidShape(6, 7, 0, 10, 11, 4),
            createCuboidShape(4, 8, 3, 6, 10, 5),
            createCuboidShape(10, 8, 3, 12, 10, 5)
    );

    public SurveillanceCameraBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    public static void trySabotageNearby(ServerWorld world, WatcherEntity watcher) {
        if (watcher.age % 40 != 0) {
            return;
        }
        BlockPos origin = watcher.getBlockPos();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos candidate = origin.add(dx, dy, dz);
                    if (candidate.getSquaredDistance(watcher.getPos()) > 9.0D
                            || !world.getBlockState(candidate).isOf(TheWatcher.SURVEILLANCE_CAMERA)) {
                        continue;
                    }
                    if (watcher.getRandom().nextInt(4) == 0
                            && world.breakBlock(candidate, true, watcher)) {
                        watcher.playSound(SoundEvents.BLOCK_IRON_BREAK, 0.8F, 0.55F);
                    }
                    return;
                }
            }
        }
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new SurveillanceCameraBlockEntity(pos, state);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    protected void appendProperties(StateManager.Builder<net.minecraft.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.get(FACING)));
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return BODY;
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            World world, BlockState state, BlockEntityType<T> type) {
        return world.isClient ? null : validateTicker(world, type,
                TheWatcher.CAMERA_BLOCK_ENTITY, SurveillanceCameraBlockEntity::tick);
    }
}
