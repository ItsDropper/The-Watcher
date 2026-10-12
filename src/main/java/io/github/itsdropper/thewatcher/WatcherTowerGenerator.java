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
    private static final int MAX_STRUCTURES_PER_SESSION = 10;
    private static int guaranteedStructuresMask;
    private static int towersBuilt;

    private WatcherTowerGenerator() {
    }

    public static void resetForRun() {
        BUILT_REGIONS.clear();
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
            // A 22-chunk square keeps even diagonal candidates within 32 chunks of spawn.
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

        // Interior furnishings and the same lore chest, now tucked against a wall.
        BlockPos chestPos = new BlockPos(x0 + 2, y0 + 1, z0 + 2);
        world.setBlockState(chestPos, net.minecraft.block.Blocks.CHEST.getDefaultState()
                .with(ChestBlock.FACING, Direction.NORTH), 3);
        if (world.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
            ItemStack logbook = new ItemStack(Items.WRITABLE_BOOK);
            logbook.set(DataComponentTypes.WRITABLE_BOOK_CONTENT, new WritableBookContentComponent(List.of(
                    RawFilteredPair.of("We built this tower after the first night. The tracks below it stopped at the tree line, though none of us heard anyone approach."),
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
        world.setBlockState(new BlockPos(x + 3, y + 1, z + 2),
                net.minecraft.block.Blocks.BARREL.getDefaultState(), 3);
        placeLoreChest(world, new BlockPos(x + 2, y + 1, z + 2), Direction.NORTH, List.of(
                "The cabin was empty when I found it. The fire was cold, but there were fresh footprints around the bed. They stopped at the wall.",
                "I watched the tree line until sunrise. It never moved while I looked directly at it. When I checked the window again, the glass was on the floor and the door was open.",
                "I have started counting the footsteps outside. There are always two more than the number I hear.",
                "If you found this, do not sleep here. It knows this place better than you do. - E."
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
        placeLoreChest(world, new BlockPos(x, y + 1, z + 2), Direction.SOUTH, List.of(
                "Fragment 1: We thought it was a spirit. We built a place for it and left offerings so it would stay away from our homes.",
                "Fragment 2: The offerings were untouched. The next night, every candle was burning, though no one had lit them.",
                "Fragment 3: It does not want food. It wants witnesses. Each person who sees it makes the silence around it feel more complete.",
                "Fragment 4: We are dismantling the shrine. If the Watcher appears between the pillars, do not count them. There are never the same number twice."
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
        placeLoreChest(world, new BlockPos(x + 2, y + 1, z - 2), Direction.WEST, List.of(
                "Expedition log, day 4: There are three of us. Every morning we find four sets of footprints around the camp.",
                "Day 6: Mara says she saw someone standing where the river bends. I told her it was a spruce trunk. There are no spruce trees on that bank.",
                "Day 8: We took turns staying awake. All three of us remember hearing the fourth person speaking, but none of us remembers the words.",
                "Last entry: I can hear my friend outside the tent. He is asleep beside me. I am not opening the flap."
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

        Item[] supplies = {Items.BREAD, Items.TORCH, Items.STRING, Items.IRON_NUGGET,
                Items.PAPER, Items.COAL, Items.ARROW, Items.BONE};
        for (int slot = 1; slot <= 4; slot++) {
            Item item = supplies[world.random.nextInt(supplies.length)];
            chest.setStack(slot, new ItemStack(item, 1 + world.random.nextInt(3)));
        }
        chest.markDirty();
    }

}
