package io.github.itsdropper.thewatcher;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;

import java.util.ArrayList;
import java.util.List;

/**
 * Gives new Watcher-mode runs a forest-biased starting location and marks the
 * nearby woods as subtly blighted. This is run initialization, not per-chunk work.
 */
public final class WatcherForestSpawn {
    private static final int SEARCH_RADIUS = 512;
    private static final int SAMPLE_STEP = 16;
    private static final int GROVE_RADIUS = 34;

    private WatcherForestSpawn() {
    }

    /**
     * Finds a safe forest surface near the vanilla spawn, moves nearby starting
     * players there, and sets the world's respawn point. If no suitable forest
     * exists nearby, the vanilla spawn is retained.
     */
    public static boolean prepare(ServerWorld world) {
        BlockPos originalSpawn = world.getSpawnPos();
        BlockPos forestSpawn = findForestSpawn(world, originalSpawn);
        if (forestSpawn == null) {
            TheWatcher.LOGGER.warn("No safe forest spawn found within {} blocks; keeping vanilla spawn.",
                    SEARCH_RADIUS);
            return false;
        }

        world.setSpawnPos(forestSpawn, 0.0F);
        for (ServerPlayerEntity player : world.getPlayers()) {
            // Only relocate players who are still at the initial spawn area. Do not
            // pull established multiplayer participants across the world.
            if (player.squaredDistanceTo(originalSpawn.getX() + 0.5D,
                    originalSpawn.getY(), originalSpawn.getZ() + 0.5D) <= 128.0D * 128.0D) {
                player.requestTeleport(forestSpawn.getX() + 0.5D, forestSpawn.getY(), forestSpawn.getZ() + 0.5D);
                player.sendMessage(net.minecraft.text.Text.literal(
                        "The woods feel quieter than they should."), false);
            }
        }

        scarStartingGrove(world, forestSpawn);
        TheWatcher.LOGGER.info("Selected Watcher forest spawn at {}", forestSpawn);
        return true;
    }

    private static BlockPos findForestSpawn(ServerWorld world, BlockPos origin) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        // Biome samples do not require generating every sampled chunk. Surface
        // height is only queried for promising forest candidates.
        for (int radius = 0; radius <= SEARCH_RADIUS; radius += SAMPLE_STEP) {
            for (int dx = -radius; dx <= radius; dx += SAMPLE_STEP) {
                for (int dz = -radius; dz <= radius; dz += SAMPLE_STEP) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    int x = origin.getX() + dx;
                    int z = origin.getZ() + dz;
                    BlockPos biomeSample = new BlockPos(x, world.getSeaLevel(), z);
                    String biomePath = world.getBiome(biomeSample).getKey()
                            .map(key -> key.getValue().getPath()).orElse("");
                    int biomePreference = forestPreference(biomePath);
                    if (biomePreference == 0) continue;

                    double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
                    double score = distance - biomePreference * 18.0D;
                    if (score >= bestScore) continue;

                    int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos feet = new BlockPos(x, y, z);
                    BlockPos ground = feet.down();
                    if (y <= world.getBottomY() + 2
                            || !world.getBlockState(ground).isSolidBlock(world, ground)
                            || !world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                            || !world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()
                            || !world.getBlockState(feet).getFluidState().isEmpty()
                            || !world.getBlockState(feet.up()).getFluidState().isEmpty()) {
                        continue;
                    }

                    // Avoid spawning directly under a low canopy or on the edge of water.
                    if (!world.getBlockState(feet.up(2)).getCollisionShape(world, feet.up(2)).isEmpty()) {
                        continue;
                    }
                    best = feet;
                    bestScore = score;
                }
            }
            // Once a nearby forest has been found, do not search farther just to
            // gain a tiny biome-preference bonus.
            if (best != null && radius >= 128) break;
        }
        return best;
    }

    private static int forestPreference(String biomePath) {
        if (biomePath.contains("dark_forest")) return 5;
        if (biomePath.contains("old_growth_taiga")) return 4;
        if (biomePath.contains("taiga") || biomePath.contains("grove")) return 3;
        if (biomePath.contains("forest")) return 2;
        if (biomePath.contains("wooded")) return 1;
        return 0;
    }

    private static void scarStartingGrove(ServerWorld world, BlockPos center) {
        // This decoration is deliberately bounded and only edits natural surfaces
        // or empty spaces in already-loaded chunks.
        for (int i = 0; i < 110; i++) {
            double angle = world.random.nextDouble() * Math.PI * 2.0D;
            double radius = 5.0D + world.random.nextDouble() * (GROVE_RADIUS - 5.0D);
            int x = center.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * radius);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

            int surfaceY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos surface = new BlockPos(x, surfaceY - 1, z);
            BlockState ground = world.getBlockState(surface);
            if (!(ground.isOf(Blocks.GRASS_BLOCK) || ground.isOf(Blocks.DIRT)
                    || ground.isOf(Blocks.PODZOL) || ground.isOf(Blocks.COARSE_DIRT)
                    || ground.isOf(Blocks.ROOTED_DIRT) || ground.isOf(Blocks.MYCELIUM))) continue;

            int roll = world.random.nextInt(10);
            BlockState scar = roll < 4 ? Blocks.COARSE_DIRT.getDefaultState()
                    : roll < 7 ? Blocks.ROOTED_DIRT.getDefaultState()
                    : roll < 9 ? Blocks.PODZOL.getDefaultState()
                    : Blocks.GRAVEL.getDefaultState();
            world.setBlockState(surface, scar, 3);

            if (world.random.nextInt(10) == 0 && world.getBlockState(surface.up()).isAir()) {
                world.setBlockState(surface.up(), Blocks.DEAD_BUSH.getDefaultState(), 3);
            }
            if (world.random.nextInt(18) == 0 && world.getBlockState(surface.up()).isAir()) {
                world.setBlockState(surface.up(), Blocks.COBWEB.getDefaultState(), 3);
            }
        }

        int treesPlaced = 0;
        for (int attempt = 0; attempt < 36 && treesPlaced < 5; attempt++) {
            double angle = world.random.nextDouble() * Math.PI * 2.0D;
            double radius = 12.0D + world.random.nextDouble() * 22.0D;
            int x = center.getX() + (int) Math.round(Math.cos(angle) * radius);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * radius);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

            int surfaceY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos ground = new BlockPos(x, surfaceY - 1, z);
            if (!(world.getBlockState(ground).isOf(Blocks.GRASS_BLOCK)
                    || world.getBlockState(ground).isOf(Blocks.DIRT)
                    || world.getBlockState(ground).isOf(Blocks.PODZOL))) continue;

            BlockPos trunk = ground.up();
            int height = 4 + world.random.nextInt(3);
            boolean clear = true;
            for (int dy = 0; dy < height + 2; dy++) {
                if (!world.getBlockState(trunk.up(dy)).isAir()) {
                    clear = false;
                    break;
                }
            }
            if (!clear) continue;

            for (int dy = 0; dy < height; dy++) {
                world.setBlockState(trunk.up(dy), Blocks.STRIPPED_DARK_OAK_LOG.getDefaultState(), 3);
            }
            // Broken, asymmetric limbs create silhouettes rather than a neat tree shape.
            for (int branch = 0; branch < 2 + world.random.nextInt(3); branch++) {
                int dy = 1 + world.random.nextInt(height - 1);
                int directionX = world.random.nextBoolean() ? 1 : -1;
                int directionZ = world.random.nextBoolean() ? 1 : -1;
                BlockPos limb = trunk.up(dy).add(directionX, 0, directionZ);
                if (world.getBlockState(limb).isAir()) {
                    world.setBlockState(limb, Blocks.DARK_OAK_FENCE.getDefaultState(), 3);
                    if (world.random.nextBoolean() && world.getBlockState(limb.up()).isAir()) {
                        world.setBlockState(limb.up(), Blocks.COBWEB.getDefaultState(), 3);
                    }
                }
            }
            treesPlaced++;
        }
    }
}
