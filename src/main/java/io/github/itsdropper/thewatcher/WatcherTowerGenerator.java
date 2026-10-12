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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds a small, old watchtower in explored, already-loaded terrain. */
public final class WatcherTowerGenerator {
    private static final Set<Long> BUILT_REGIONS = new HashSet<>();
    private static final Map<Integer, BlockPos> GUARANTEED_POSITIONS = new HashMap<>();
    private static final int MAX_STRUCTURES_PER_SESSION = 10;
    private static int guaranteedStructuresMask;
    private static int towersBuilt;

    private WatcherTowerGenerator() {
    }

    public static void resetForRun() {
        BUILT_REGIONS.clear();
        GUARANTEED_POSITIONS.clear();
        towersBuilt = 0;
        guaranteedStructuresMask = 0;
    }

    public static Set<Long> snapshotBuiltRegions() {
        return Set.copyOf(BUILT_REGIONS);
    }

    public static void restoreBuiltRegions(Set<Long> regions) {
        BUILT_REGIONS.clear();
        BUILT_REGIONS.addAll(regions);
        towersBuilt = BUILT_REGIONS.size();
    }

    public static int snapshotGuaranteedStructuresMask() {
        return guaranteedStructuresMask;
    }

    public static Map<Integer, BlockPos> snapshotGuaranteedPositions() {
        return Map.copyOf(GUARANTEED_POSITIONS);
    }

    public static void restoreGuaranteedPositions(Map<Integer, BlockPos> positions) {
        GUARANTEED_POSITIONS.clear();
        GUARANTEED_POSITIONS.putAll(positions);
    }

    public static void restoreGuaranteedStructuresMask(int mask) {
        guaranteedStructuresMask = mask & 15;
    }

    /**
     * Prepares a nearby set of story locations on first run instead of relying on
     * a lucky random attempt in an already-loaded, perfectly flat chunk.
     */
    public static boolean generateGuaranteedStructures(ServerWorld world) {
        int before = guaranteedStructuresMask;
        generateGuaranteedSpawnTower(world);
        generateGuaranteedSite(world, 2, new int[][]{{11, 2}, {12, 5}, {10, 7}, {13, -2}},
                WatcherTowerGenerator::buildAbandonedCabin);
        generateGuaranteedSite(world, 4, new int[][]{{-11, 3}, {-12, -4}, {-10, 7}, {-13, 0}},
                WatcherTowerGenerator::buildRitualShrine);
        generateGuaranteedSite(world, 8, new int[][]{{3, -11}, {-4, -12}, {7, -10}, {0, -13}},
                WatcherTowerGenerator::buildRuinedCamp);
        return before != guaranteedStructuresMask;
    }

    private static void generateGuaranteedSite(ServerWorld world, int bit, int[][] offsets,
                                               java.util.function.BiConsumer<ServerWorld, BlockPos> builder) {
        if ((guaranteedStructuresMask & bit) != 0) {
            return;
        }

        BlockPos spawn = world.getSpawnPos();
        for (int[] offset : offsets) {
            int chunkX = (spawn.getX() >> 4) + offset[0];
            int chunkZ = (spawn.getZ() >> 4) + offset[1];
            if ((long) chunkX * chunkX + (long) chunkZ * chunkZ < 100L) {
                continue;
            }

            int x = (chunkX << 4) + 8;
            int z = (chunkZ << 4) + 8;
            int regionX = Math.floorDiv(x, 128);
            int regionZ = Math.floorDiv(z, 128);
            long regionKey = ((long) regionX << 32) ^ (regionZ & 0xffffffffL);
            if (BUILT_REGIONS.contains(regionKey)) {
                continue;
            }

            // Generate only the few chunks covered by the site footprint.
            for (int dx = -5; dx <= 5; dx++) {
                for (int dz = -5; dz <= 5; dz++) {
                    world.getChunk((x + dx) >> 4, (z + dz) >> 4);
                }
            }
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y <= world.getBottomY() + 2 || y + 15 >= world.getTopY()) {
                continue;
            }

            // Clear and level a compact site footprint, matching the guaranteed tower policy.
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

            builder.accept(world, new BlockPos(x, y, z));
            BUILT_REGIONS.add(regionKey);
            towersBuilt++;
            guaranteedStructuresMask |= bit;
            GUARANTEED_POSITIONS.put(bit, new BlockPos(x, y, z));
            TheWatcher.LOGGER.info("Generated guaranteed Watcher story site {} at {}", bit, new BlockPos(x, y, z));
            return;
        }
        TheWatcher.LOGGER.warn("Could not place Watcher story site bit {}; it will be retried.", bit);
    }

    /**
     * Creates a guaranteed starting tower before the player needs to search for one.
     * A candidate chunk is generated on demand, then a small level platform is prepared
     * so mountains, forests, and uneven terrain cannot make the starting tower fail.
     */
    public static boolean generateGuaranteedSpawnTower(ServerWorld world) {
        if ((guaranteedStructuresMask & 1) != 0) {
            return true;
        }

        BlockPos spawn = world.getSpawnPos();
        for (int attempt = 0; attempt < 48; attempt++) {
            // A 16-chunk square keeps the tower near spawn while allowing safe alternatives.
            int chunkOffsetX = world.random.nextInt(33) - 16;
            int chunkOffsetZ = world.random.nextInt(33) - 16;
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
            guaranteedStructuresMask |= 1;
            GUARANTEED_POSITIONS.put(1, base);
            TheWatcher.LOGGER.info("Generated guaranteed Watcher tower near world spawn at {}", base);
            return true;
        }

        TheWatcher.LOGGER.warn("Could not find a valid location for the guaranteed spawn-area Watcher tower.");
        return false;
    }

    public static boolean tryGenerate(ServerWorld world, ServerPlayerEntity player) {
        if (towersBuilt >= MAX_STRUCTURES_PER_SESSION) {
            return false;
        }

        for (int attempt = 0; attempt < 36; attempt++) {
            double angle = world.random.nextDouble() * Math.PI * 2.0D;
            double radius = 72.0D + world.random.nextDouble() * 88.0D;
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * radius);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * radius);
            int candidateChunkX = x >> 4;
            int candidateChunkZ = z >> 4;
            if ((long) candidateChunkX * candidateChunkX
                    + (long) candidateChunkZ * candidateChunkZ < 100L) {
                continue;
            }
            if (!world.isChunkLoaded(candidateChunkX, candidateChunkZ)) {
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

            BlockPos base = new BlockPos(x, baseY, z);
            // The first structure is always the guaranteed tower; later discoveries vary.
            switch (world.random.nextInt(3)) {
                case 0 -> buildAbandonedCabin(world, base);
                case 1 -> buildRitualShrine(world, base);
                default -> buildRuinedCamp(world, base);
            }
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
                for (int dy = 0; dy < 15; dy++) {
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

        // Projecting timber braces on each face break up the flat stone walls.
        for (int offset = -2; offset <= 2; offset++) {
            world.setBlockState(new BlockPos(x0 + offset, y0 + 5, z0 - 5), wood, 3);
            world.setBlockState(new BlockPos(x0 + offset, y0 + 9, z0 + 5), wood, 3);
            world.setBlockState(new BlockPos(x0 - 5, y0 + 5, z0 + offset), wood, 3);
            world.setBlockState(new BlockPos(x0 + 5, y0 + 9, z0 + offset), wood, 3);
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

        // A lived-in lookout: charting table, supply barrel, and a light that feels too cold.
        world.setBlockState(new BlockPos(x0 - 2, y0 + 1, z0 - 2), net.minecraft.block.Blocks.CARTOGRAPHY_TABLE.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x0 - 2, y0 + 1, z0 + 2), net.minecraft.block.Blocks.BARREL.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x0, y0 + 7, z0), net.minecraft.block.Blocks.SOUL_LANTERN.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x0 + 3, y0 + 7, z0 + 3), net.minecraft.block.Blocks.COBWEB.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x0 - 3, y0 + 7, z0 - 3), net.minecraft.block.Blocks.COBWEB.getDefaultState(), 3);

        // Interior furnishings and lore chest, tucked against a wall.
        BlockPos chestPos = new BlockPos(x0 + 2, y0 + 1, z0 + 2);
        world.setBlockState(chestPos, net.minecraft.block.Blocks.CHEST.getDefaultState()
                .with(ChestBlock.FACING, Direction.NORTH), 3);
        if (world.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
            ItemStack logbook = new ItemStack(Items.WRITABLE_BOOK);
            logbook.set(DataComponentTypes.WRITABLE_BOOK_CONTENT, new WritableBookContentComponent(List.of(
                    RawFilteredPair.of("FIELD REPORT 01 — The tower was built after the first night. Tracks crossed the clearing and stopped at the wall. No tracks led away. The door was barred from inside."),
                    RawFilteredPair.of("We tried keeping watch in pairs. Whoever stared into the trees saw nothing. Whoever looked down to write found a figure standing closer when they looked up. It never crossed the open ground."),
                    RawFilteredPair.of("It repeats small things: a footstep after yours, a knock from the wrong side of a door, a voice saying a name nobody used aloud. Do not answer a voice you cannot see."),
                    RawFilteredPair.of("At 03:17, every camera showed the same empty corridor. The monitor in the locked room showed all four of us standing behind the operator. There were only three of us on shift."),
                    RawFilteredPair.of("If you found this tower, take the supplies. Do not sleep here. The top floor is not safer; it only gives you fewer directions to look. — M.")
            )));
            chest.setStack(0, logbook);

            Item[] supplies = {Items.BREAD, Items.COOKED_BEEF, Items.TORCH, Items.IRON_INGOT,
                    Items.STRING, Items.ARROW, Items.COMPASS, Items.PAPER, Items.SPYGLASS,
                    TheWatcher.SURVEILLANCE_CAMERA_ITEM};
            for (int slot = 1; slot <= 5; slot++) {
                Item item = supplies[world.random.nextInt(supplies.length)];
                int count = item == Items.IRON_INGOT || item == Items.COMPASS || item == Items.SPYGLASS
                        || item == TheWatcher.SURVEILLANCE_CAMERA_ITEM
                        ? 1 : 1 + world.random.nextInt(4);
                chest.setStack(slot, new ItemStack(item, count));
            }
            chest.markDirty();
        }
    }

    private static void buildAbandonedCabin(ServerWorld world, BlockPos base) {
        int x = base.getX(), y = base.getY(), z = base.getZ();
        BlockState floor = net.minecraft.block.Blocks.SPRUCE_PLANKS.getDefaultState();
        BlockState wall = net.minecraft.block.Blocks.SPRUCE_LOG.getDefaultState();
        BlockState roof = net.minecraft.block.Blocks.DARK_OAK_PLANKS.getDefaultState();
        BlockState air = net.minecraft.block.Blocks.AIR.getDefaultState();

        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                world.setBlockState(new BlockPos(x + dx, y, z + dz), floor, 3);
                for (int dy = 1; dy <= 4; dy++) {
                    boolean edge = Math.abs(dx) == 4 || Math.abs(dz) == 4;
                    if (!edge) continue;
                    boolean doorway = dz == -4 && dx == 0 && dy <= 2;
                    boolean window = dy == 2 && ((dz == -4 && Math.abs(dx) == 2)
                            || (dx == 4 && dz == 0) || (dx == -4 && dz == 0));
                    world.setBlockState(new BlockPos(x + dx, y + dy, z + dz),
                            doorway || window ? air : wall, 3);
                }
            }
        }
        for (int layer = 0; layer < 4; layer++) {
            int radius = 4 - layer;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) == radius || Math.abs(dz) == radius) {
                        world.setBlockState(new BlockPos(x + dx, y + 5 + layer, z + dz), roof, 3);
                    }
                }
            }
        }
        world.setBlockState(new BlockPos(x - 2, y + 1, z - 2),
                net.minecraft.block.Blocks.COBWEB.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x + 3, y + 1, z + 2), net.minecraft.block.Blocks.BARREL.getDefaultState(), 3);
        // Signs of a hurried retreat: a cold hearth, boarded sightlines, and a place set for nobody.
        world.setBlockState(new BlockPos(x, y + 1, z), net.minecraft.block.Blocks.CAMPFIRE.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x - 3, y + 1, z - 3), net.minecraft.block.Blocks.CAULDRON.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x + 3, y + 1, z - 3), net.minecraft.block.Blocks.SOUL_LANTERN.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x - 3, y + 1, z + 3), net.minecraft.block.Blocks.COBWEB.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x + 2, y + 3, z - 4), net.minecraft.block.Blocks.DARK_OAK_TRAPDOOR.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x - 2, y + 3, z - 4), net.minecraft.block.Blocks.DARK_OAK_TRAPDOOR.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x + 1, y + 1, z + 1), net.minecraft.block.Blocks.SPRUCE_STAIRS.getDefaultState(), 3);
        placeLoreChest(world, new BlockPos(x + 2, y + 1, z + 2), Direction.NORTH, List.of(
                "E.'s journal — Entry 1. I found the cabin with smoke still in the chimney and nobody inside. One bowl was warm. There were wet footprints around the table, but the floorboards beyond the door were dry.",
                "Entry 2. I nailed planks over the windows. In the morning, the nails were still in the wood, but the boards had been fitted from the outside. I live alone. I have never owned a second hammer.",
                "Entry 3. Three knocks came from beneath the floor. I moved the bed and found no cellar. The knocks followed me to the other side of the room, always from below my feet.",
                "Last entry. I heard my brother call from the woods. He has been dead for nine years. The voice asked me to open the door so it could stop standing in the rain. The rain had not started yet. Do not stay here."
        ));
    }

    private static void buildRitualShrine(ServerWorld world, BlockPos base) {
        int x = base.getX(), y = base.getY(), z = base.getZ();
        BlockState mossy = net.minecraft.block.Blocks.MOSSY_COBBLESTONE.getDefaultState();
        BlockState stone = net.minecraft.block.Blocks.COBBLESTONE.getDefaultState();
        BlockState deepslate = net.minecraft.block.Blocks.COBBLED_DEEPSLATE.getDefaultState();

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.abs(dx) + Math.abs(dz) <= 5) {
                    world.setBlockState(new BlockPos(x + dx, y, z + dz), mossy, 3);
                }
            }
        }
        for (int dx : new int[]{-3, 3}) {
            for (int dz : new int[]{-3, 3}) {
                for (int dy = 1; dy <= 3; dy++) {
                    world.setBlockState(new BlockPos(x + dx, y + dy, z + dz), stone, 3);
                }
                world.setBlockState(new BlockPos(x + dx, y + 4, z + dz), deepslate, 3);
                world.setBlockState(new BlockPos(x + dx, y + 3, z + dz),
                        net.minecraft.block.Blocks.CHAIN.getDefaultState(), 3);
            }
        }
        world.setBlockState(new BlockPos(x, y + 1, z), deepslate, 3);
        world.setBlockState(new BlockPos(x, y + 2, z), net.minecraft.block.Blocks.BLACK_CANDLE.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x - 1, y + 1, z), mossy, 3);
        world.setBlockState(new BlockPos(x + 1, y + 1, z), mossy, 3);
        // Deliberate asymmetry: the fourth corner is missing, but its chain still hangs.
        world.setBlockState(new BlockPos(x - 3, y + 1, z), net.minecraft.block.Blocks.SOUL_LANTERN.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x + 3, y + 1, z), net.minecraft.block.Blocks.SOUL_LANTERN.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x - 2, y + 1, z - 2), net.minecraft.block.Blocks.COBWEB.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x + 2, y + 1, z - 2), net.minecraft.block.Blocks.COBWEB.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x, y + 1, z - 3), net.minecraft.block.Blocks.BLACK_CANDLE.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x, y + 1, z + 3), net.minecraft.block.Blocks.BLACK_CANDLE.getDefaultState(), 3);
        placeLoreChest(world, new BlockPos(x, y + 1, z + 2), Direction.SOUTH, List.of(
                "ARCHIVE FRAGMENT A. We built the shrine after the same dream reached seven houses in one night. Everyone described a person standing just beyond the doorway. Nobody described a face.",
                "FRAGMENT B. The offerings remained untouched. We began leaving written names instead. The next morning, every name had been crossed out except those of people who had not yet been born.",
                "FRAGMENT C. We tried counting the pillars to prove the dream was only a dream. At dusk there were four. At dawn, five shadows crossed the floor, though the fifth pillar had no body and cast no shadow of its own.",
                "FINAL FRAGMENT. Pull down the shrine. Burn the names. Do not look into the dark space between the chains. It is not an altar. It is a place where something learned to wait."
        ));
    }

    private static void buildRuinedCamp(ServerWorld world, BlockPos base) {
        int x = base.getX(), y = base.getY(), z = base.getZ();
        BlockState planks = net.minecraft.block.Blocks.OAK_PLANKS.getDefaultState();
        BlockState fence = net.minecraft.block.Blocks.OAK_FENCE.getDefaultState();
        BlockState gravel = net.minecraft.block.Blocks.GRAVEL.getDefaultState();
        BlockState cobble = net.minecraft.block.Blocks.MOSSY_COBBLESTONE.getDefaultState();

        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (Math.abs(dx) == 4 || Math.abs(dz) == 4) {
                    world.setBlockState(new BlockPos(x + dx, y, z + dz), gravel, 3);
                }
            }
        }
        for (int dx : new int[]{-3, 3}) {
            world.setBlockState(new BlockPos(x + dx, y + 1, z - 2), fence, 3);
            world.setBlockState(new BlockPos(x + dx, y + 1, z + 2), fence, 3);
            world.setBlockState(new BlockPos(x + dx, y + 2, z), planks, 3);
        }
        world.setBlockState(new BlockPos(x, y + 1, z), net.minecraft.block.Blocks.CAMPFIRE.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x - 3, y + 1, z + 3), cobble, 3);
        world.setBlockState(new BlockPos(x - 3, y + 2, z + 3), net.minecraft.block.Blocks.OAK_SIGN.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x + 3, y + 1, z + 3), cobble, 3);
        // A collapsed perimeter and half-packed camp: the party left useful gear behind.
        world.setBlockState(new BlockPos(x - 2, y + 1, z - 2), net.minecraft.block.Blocks.BARREL.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x + 2, y + 1, z + 2), net.minecraft.block.Blocks.SOUL_LANTERN.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x - 4, y + 1, z + 1), net.minecraft.block.Blocks.COBWEB.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x + 4, y + 1, z - 1), net.minecraft.block.Blocks.COBWEB.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x - 3, y + 1, z - 3), net.minecraft.block.Blocks.SKELETON_SKULL.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x + 3, y + 1, z - 3), net.minecraft.block.Blocks.SKELETON_SKULL.getDefaultState(), 3);
        world.setBlockState(new BlockPos(x, y + 1, z + 3), net.minecraft.block.Blocks.OAK_PRESSURE_PLATE.getDefaultState(), 3);
        placeLoreChest(world, new BlockPos(x + 2, y + 1, z - 2), Direction.WEST, List.of(
                "EXPEDITION LOG — DAY 4. There are three of us. Each morning there are four sets of footprints around the fire. The fourth set is always dry, even after rain, and ends beside the sleeping bag of whoever kept watch.",
                "DAY 6. Mara saw someone at the river bend. I blamed the fog. She drew the figure in her notebook; when we checked later, the page showed our camp from above. One tent had a person standing inside it. We had only brought two tents.",
                "DAY 8. We made a roster so nobody could invent a fourth voice. At midnight the roster had four names. The new name was written in my handwriting. I have not told the others.",
                "LAST ENTRY. I can hear my friend outside the tent. He is asleep beside me. The voice outside is asking me to come help carry something. It knows the name we gave the missing person. We never told it that name."
        ));
    }

    private static void placeLoreChest(ServerWorld world, BlockPos pos, Direction facing, List<String> pages) {
        world.setBlockState(pos, net.minecraft.block.Blocks.CHEST.getDefaultState()
                .with(ChestBlock.FACING, facing), 3);
        if (!(world.getBlockEntity(pos) instanceof ChestBlockEntity chest)) return;

        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        book.set(DataComponentTypes.WRITABLE_BOOK_CONTENT, new WritableBookContentComponent(
                pages.stream().map(RawFilteredPair::of).toList()));
        chest.setStack(0, book);

        // Reward exploration properly: survival supplies, navigation tools, and a few rare finds.
        Item[] supplies = {
                Items.COOKED_BEEF, Items.BREAD, Items.TORCH, Items.SOUL_TORCH,
                Items.IRON_INGOT, Items.GOLD_INGOT, Items.ARROW, Items.STRING,
                Items.COMPASS, Items.SPYGLASS, Items.GOLDEN_APPLE, Items.IRON_PICKAXE,
                Items.SHIELD, Items.NAME_TAG, Items.ENDER_PEARL,
                TheWatcher.SURVEILLANCE_CAMERA_ITEM
        };
        for (int slot = 1; slot <= 8; slot++) {
            Item item = supplies[world.random.nextInt(supplies.length)];
            boolean single = item == Items.COMPASS || item == Items.SPYGLASS
                    || item == Items.GOLDEN_APPLE || item == Items.IRON_PICKAXE
                    || item == Items.SHIELD || item == Items.NAME_TAG
                    || item == Items.ENDER_PEARL || item == TheWatcher.SURVEILLANCE_CAMERA_ITEM;
            int count = single ? 1 : 2 + world.random.nextInt(5);
            chest.setStack(slot, new ItemStack(item, count));
        }
        chest.markDirty();
    }

}