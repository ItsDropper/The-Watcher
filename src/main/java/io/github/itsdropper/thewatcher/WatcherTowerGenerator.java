package io.github.itsdropper.thewatcher;

import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WritableBookContentComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.world.Heightmap;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Builds a small, old watchtower in explored, already-loaded terrain. */
public final class WatcherTowerGenerator {
    private static final Set<Long> BUILT_REGIONS = new HashSet<>();
    private static final int MAX_TOWERS_PER_SESSION = 5;
    private static int towersBuilt;

    private WatcherTowerGenerator() {
    }

    public static void resetForRun() {
        BUILT_REGIONS.clear();
        towersBuilt = 0;
    }

    public static Set<Long> snapshotBuiltRegions() {
        return Set.copyOf(BUILT_REGIONS);
    }

    public static void restoreBuiltRegions(Set<Long> regions) {
        BUILT_REGIONS.clear();
        BUILT_REGIONS.addAll(regions);
        towersBuilt = BUILT_REGIONS.size();
    }

    public static boolean tryGenerate(ServerWorld world, ServerPlayerEntity player) {
        if (towersBuilt >= MAX_TOWERS_PER_SESSION) {
            return false;
        }

        for (int attempt = 0; attempt < 36; attempt++) {
            double angle = world.random.nextDouble() * Math.PI * 2.0D;
            double radius = 72.0D + world.random.nextDouble() * 88.0D;
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * radius);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * radius);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }

            int regionX = Math.floorDiv(x, 128);
            int regionZ = Math.floorDiv(z, 128);
            long regionKey = ((long) regionX << 32) ^ (regionZ & 0xffffffffL);
            if (BUILT_REGIONS.contains(regionKey)) {
                continue;
            }

            int baseY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (!isFlatAndClear(world, x, baseY, z)) {
                continue;
            }

            buildTower(world, new BlockPos(x, baseY, z));
            BUILT_REGIONS.add(regionKey);
            towersBuilt++;
            return true;
        }
        return false;
    }

    private static boolean isFlatAndClear(ServerWorld world, int x, int baseY, int z) {
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                int px = x + dx;
                int pz = z + dz;
                if (!world.isChunkLoaded(px >> 4, pz >> 4)) {
                    return false;
                }
                int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, px, pz);
                if (Math.abs(top - baseY) > 1) {
                    return false;
                }
                for (int dy = 0; dy < 11; dy++) {
                    BlockPos pos = new BlockPos(px, baseY + dy, pz);
                    if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) {
                        return false;
                    }
                }
                BlockPos ground = new BlockPos(px, baseY - 1, pz);
                if (!world.getBlockState(ground).isSolidBlock(world, ground)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void buildTower(ServerWorld world, BlockPos base) {
        int x0 = base.getX();
        int y0 = base.getY();
        int z0 = base.getZ();
        BlockState stone = net.minecraft.block.Blocks.STONE_BRICKS.getDefaultState();
        BlockState weathered = net.minecraft.block.Blocks.MOSSY_STONE_BRICKS.getDefaultState();
        BlockState roof = net.minecraft.block.Blocks.COBBLED_DEEPSLATE.getDefaultState();

        // A compact 9x9 footprint with a hollow shaft, narrow windows and a roof silhouette.
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                world.setBlockState(new BlockPos(x0 + dx, y0, z0 + dz), stone, 3);
                for (int dy = 1; dy <= 9; dy++) {
                    boolean wall = Math.abs(dx) == 4 || Math.abs(dz) == 4;
                    if (!wall) continue;
                    boolean doorway = dz == -4 && dx == 0 && dy <= 2;
                    boolean windowGap = doorway || ((dy == 4 || dy == 7)
                            && ((dx == 0 && Math.abs(dz) == 4) || (dz == 0 && Math.abs(dx) == 4)));
                    if (!windowGap) {
                        BlockState material = ((dx + dz + dy) % 5 == 0) ? weathered : stone;
                        world.setBlockState(new BlockPos(x0 + dx, y0 + dy, z0 + dz), material, 3);
                    }
                }
            }
        }

        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (Math.abs(dx) == 4 || Math.abs(dz) == 4 || dx == 0 || dz == 0) {
                    world.setBlockState(new BlockPos(x0 + dx, y0 + 10, z0 + dz), roof, 3);
                }
            }
        }

        // Interior access ladder and a single chest with a fixed historical log plus random supplies.
        for (int dy = 1; dy <= 7; dy++) {
            BlockPos ladderPos = new BlockPos(x0, y0 + dy, z0 - 3);
            world.setBlockState(ladderPos, net.minecraft.block.Blocks.LADDER.getDefaultState()
                    .with(net.minecraft.block.LadderBlock.FACING, Direction.SOUTH), 3);
        }

        BlockPos chestPos = new BlockPos(x0 + 1, y0 + 1, z0 + 1);
        world.setBlockState(chestPos, net.minecraft.block.Blocks.CHEST.getDefaultState()
                .with(ChestBlock.FACING, Direction.NORTH), 3);
        if (world.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
            ItemStack logbook = new ItemStack(Items.WRITABLE_BOOK);
            logbook.set(DataComponentTypes.WRITABLE_BOOK_CONTENT, new WritableBookContentComponent(List.of(
                    RawFilteredPair.of("Field log, entry 4\n\nWe built this tower after the first night. The tracks below it stopped at the tree line, though none of us heard anyone approach."),
                    RawFilteredPair.of("It does not hunt like an animal. It watches until you begin to expect it. Then it changes where it stands. Never trust a place just because it was empty a moment ago."),
                    RawFilteredPair.of("We called it the Watcher. We do not know whether it came with this forest or learned to imitate the people who lived here before us. It remembers where we look."),
                    RawFilteredPair.of("If the message says it has joined the game, do not assume it has only just arrived. Keep this tower in mind. We left what we could spare. - M.")
            )));
            chest.setStack(0, logbook);

            Item[] supplies = {Items.BREAD, Items.COOKED_BEEF, Items.TORCH, Items.IRON_INGOT,
                    Items.STRING, Items.ARROW, Items.COMPASS, Items.PAPER, Items.SPYGLASS};
            for (int slot = 1; slot <= 5; slot++) {
                Item item = supplies[world.random.nextInt(supplies.length)];
                int count = item == Items.IRON_INGOT || item == Items.COMPASS || item == Items.SPYGLASS
                        ? 1 : 1 + world.random.nextInt(4);
                chest.setStack(slot, new ItemStack(item, count));
            }
            chest.markDirty();
        }
    }
}
