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

    /**
     * Creates a guaranteed starting tower before the player needs to search for one.
     * A candidate chunk is generated on demand, then a small level platform is prepared
     * so mountains, forests, and uneven terrain cannot make the starting tower fail.
     */
    public static boolean generateGuaranteedSpawnTower(ServerWorld world) {
        if (towersBuilt > 0) {
            return true;
        }

        BlockPos spawn = world.getSpawnPos();
        for (int attempt = 0; attempt < 48; attempt++) {
            // A 22-chunk square keeps even diagonal candidates within 32 chunks of spawn.
            int chunkOffsetX = world.random.nextInt(45) - 22;
            int chunkOffsetZ = world.random.nextInt(45) - 22;
            if (chunkOffsetX == 0 && chunkOffsetZ == 0) {
                chunkOffsetX = 1;
            }
            int chunkX = (spawn.getX() >> 4) + chunkOffsetX;
            int chunkZ = (spawn.getZ() >> 4) + chunkOffsetZ;
            // Keep the starting tower at least ten chunks from the world origin (0, 0).
            if ((long) chunkX * chunkX + (long) chunkZ * chunkZ < 100L) {
                continue;
            }
            int x = (chunkX << 4) + 8;
            int z = (chunkZ << 4) + 8;

            // Deliberately generate this candidate chunk now rather than waiting for exploration.
            world.getChunk(chunkX, chunkZ);
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y <= world.getBottomY() + 2 || y + 15 >= world.getTopY()) {
                continue;
            }

            int regionX = Math.floorDiv(x, 128);
            int regionZ = Math.floorDiv(z, 128);
            long regionKey = ((long) regionX << 32) ^ (regionZ & 0xffffffffL);
            if (BUILT_REGIONS.contains(regionKey)) {
                continue;
            }

            // Prepare a compact, level footprint. The surrounding terrain remains untouched.
            for (int dx = -5; dx <= 5; dx++) {
                for (int dz = -5; dz <= 5; dz++) {
                    for (int dy = 0; dy <= 14; dy++) {
                        world.setBlockState(new BlockPos(x + dx, y + dy, z + dz),
                                net.minecraft.block.Blocks.AIR.getDefaultState(), 3);
                    }
                    world.setBlockState(new BlockPos(x + dx, y - 1, z + dz),
                            net.minecraft.block.Blocks.STONE_BRICKS.getDefaultState(), 3);
                }
            }

            BlockPos base = new BlockPos(x, y, z);
            buildTower(world, base);
            BUILT_REGIONS.add(regionKey);
            towersBuilt++;
            TheWatcher.LOGGER.info("Generated guaranteed Watcher tower near world spawn at {}", base);
            return true;
        }

        TheWatcher.LOGGER.warn("Could not find a valid location for the guaranteed spawn-area Watcher tower.");
        return false;
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
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
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
        BlockState cracked = net.minecraft.block.Blocks.CRACKED_STONE_BRICKS.getDefaultState();
        BlockState mossy = net.minecraft.block.Blocks.MOSSY_STONE_BRICKS.getDefaultState();
        BlockState deep = net.minecraft.block.Blocks.COBBLED_DEEPSLATE.getDefaultState();
        BlockState polished = net.minecraft.block.Blocks.POLISHED_ANDESITE.getDefaultState();
        BlockState wood = net.minecraft.block.Blocks.DARK_OAK_PLANKS.getDefaultState();
        BlockState fence = net.minecraft.block.Blocks.DARK_OAK_FENCE.getDefaultState();
        BlockState air = net.minecraft.block.Blocks.AIR.getDefaultState();

        // Broad stepped foundation, with a stone border to make the tower read clearly from a distance.
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                boolean edge = Math.abs(dx) == 5 || Math.abs(dz) == 5;
                world.setBlockState(new BlockPos(x0 + dx, y0, z0 + dz), edge ? polished : stone, 3);
                if (edge) {
                    world.setBlockState(new BlockPos(x0 + dx, y0 + 1, z0 + dz), deep, 3);
                }
            }
        }

        // Tall stone shaft: thick corner piers, timber braces, slit windows, and a real doorway.
        for (int dy = 1; dy <= 10; dy++) {
            for (int dx = -5; dx <= 5; dx++) {
                for (int dz = -5; dz <= 5; dz++) {
                    boolean perimeter = Math.abs(dx) == 5 || Math.abs(dz) == 5;
                    if (!perimeter) continue;

                    boolean corner = Math.abs(dx) == 5 && Math.abs(dz) == 5;
                    boolean doorway = dz == -5 && dx >= -1 && dx <= 1 && dy <= 3;
                    boolean window = (dy == 4 || dy == 8)
                            && ((dz == -5 && (dx == -2 || dx == 2))
                            || (dz == 5 && (dx == -2 || dx == 2))
                            || (dx == -5 && (dz == -2 || dz == 2))
                            || (dx == 5 && (dz == -2 || dz == 2)));
                    boolean timberBand = dy == 5 || dy == 9;
                    if (doorway || window) {
                        world.setBlockState(new BlockPos(x0 + dx, y0 + dy, z0 + dz), air, 3);
                    } else if (corner && dy % 3 != 0) {
                        world.setBlockState(new BlockPos(x0 + dx, y0 + dy, z0 + dz), polished, 3);
                    } else if (timberBand && !corner) {
                        world.setBlockState(new BlockPos(x0 + dx, y0 + dy, z0 + dz), wood, 3);
                    } else {
                        BlockState material = world.random.nextInt(8) == 0 ? mossy
                                : world.random.nextInt(10) == 0 ? cracked : stone;
                        world.setBlockState(new BlockPos(x0 + dx, y0 + dy, z0 + dz), material, 3);
                    }
                }
            }
        }

        // Internal upper floor with an access opening, so the ladder remains usable.
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (!(dx == 0 && dz >= -4 && dz <= -2)) {
                    world.setBlockState(new BlockPos(x0 + dx, y0 + 6, z0 + dz), wood, 3);
                }
            }
        }

        // Heavy roof ring and battlements: the crenellated silhouette makes it visible over trees.
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                boolean perimeter = Math.abs(dx) == 5 || Math.abs(dz) == 5;
                if (perimeter) {
                    world.setBlockState(new BlockPos(x0 + dx, y0 + 11, z0 + dz), deep, 3);
                    boolean merlon = ((dx + dz) & 1) == 0;
                    if (merlon) {
                        world.setBlockState(new BlockPos(x0 + dx, y0 + 12, z0 + dz), stone, 3);
                    }
                } else if (Math.abs(dx) == 4 || Math.abs(dz) == 4) {
                    world.setBlockState(new BlockPos(x0 + dx, y0 + 10, z0 + dz), deep, 3);
                }
            }
        }

        // Four corner turrets, a small central lookout, and warm lights under the parapet.
        for (int dx : new int[]{-4, 4}) {
            for (int dz : new int[]{-4, 4}) {
                for (int dy = 11; dy <= 13; dy++) {
                    world.setBlockState(new BlockPos(x0 + dx, y0 + dy, z0 + dz), stone, 3);
                }
                world.setBlockState(new BlockPos(x0 + dx, y0 + 14, z0 + dz), deep, 3);
                world.setBlockState(new BlockPos(x0 + dx, y0 + 11, z0 + dz),
                        net.minecraft.block.Blocks.LANTERN.getDefaultState(), 3);
            }
        }
        world.setBlockState(new BlockPos(x0, y0 + 12, z0), polished, 3);
        world.setBlockState(new BlockPos(x0, y0 + 13, z0), polished, 3);
        world.setBlockState(new BlockPos(x0, y0 + 14, z0), deep, 3);

        // A continuous interior ladder rises from the entry to the roof lookout.
        for (int dy = 1; dy <= 10; dy++) {
            BlockPos ladderPos = new BlockPos(x0, y0 + dy, z0 - 4);
            world.setBlockState(ladderPos, net.minecraft.block.Blocks.LADDER.getDefaultState()
                    .with(net.minecraft.block.LadderBlock.FACING, Direction.SOUTH), 3);
        }

        // Interior furnishings and the same lore chest, now tucked against a wall.
        BlockPos chestPos = new BlockPos(x0 + 2, y0 + 1, z0 + 2);
        world.setBlockState(chestPos, net.minecraft.block.Blocks.CHEST.getDefaultState()
                .with(ChestBlock.FACING, Direction.NORTH), 3);
        if (world.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
            ItemStack logbook = new ItemStack(Items.WRITABLE_BOOK);
            logbook.set(DataComponentTypes.WRITABLE_BOOK_CONTENT, new WritableBookContentComponent(List.of(
                    RawFilteredPair.of("Field log, entry 4\\n\\nWe built this tower after the first night. The tracks below it stopped at the tree line, though none of us heard anyone approach."),
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
