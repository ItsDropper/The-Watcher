package io.github.itsdropper.thewatcher.entity;

/**
 * Spatial policy for long-range stalking. All distances are derived from the
 * player's configured render distance rather than a fixed block radius.
 */
public final class WatcherStalkingDirector {
    private static final int MIN_RENDER_CHUNKS = 4;
    private static final double EDGE_MARGIN_BLOCKS = 10.0D;
    private static final double INNER_JITTER_BLOCKS = 22.0D;
    private static final double MINIMUM_STALK_DISTANCE = 56.0D;

    private WatcherStalkingDirector() {
    }

    public static int renderDistanceChunks(int configuredChunks) {
        return Math.max(MIN_RENDER_CHUNKS, Math.min(64, configuredChunks));
    }

    public static double renderDistanceBlocks(int configuredChunks) {
        return renderDistanceChunks(configuredChunks) * 16.0D;
    }

    /**
     * The Watcher should sit near the outer visible ring, not in the player's
     * immediate surroundings. The narrow band keeps sightings rare and distant.
     */
    public static double minimumStalkingDistance(int configuredChunks) {
        return Math.max(MINIMUM_STALK_DISTANCE,
                renderDistanceBlocks(configuredChunks) - EDGE_MARGIN_BLOCKS - INNER_JITTER_BLOCKS);
    }

    public static double maximumStalkingDistance(int configuredChunks) {
        return Math.max(minimumStalkingDistance(configuredChunks) + 4.0D,
                renderDistanceBlocks(configuredChunks) - EDGE_MARGIN_BLOCKS);
    }

    public static boolean isTooCloseForStalking(double distance, int configuredChunks) {
        return distance < minimumStalkingDistance(configuredChunks);
    }

    public static double candidateRadius(int configuredChunks, double unitSample) {
        double sample = Math.max(0.0D, Math.min(0.999999D, unitSample));
        double min = minimumStalkingDistance(configuredChunks);
        double max = maximumStalkingDistance(configuredChunks);
        return min + (max - min) * sample;
    }

    /**
     * How far the target may be before this entity should stop tracking it.
     * Slight slack prevents target acquisition from oscillating at the edge.
     */
    public static double targetRange(int configuredChunks) {
        return renderDistanceBlocks(configuredChunks) + 48.0D;
    }
}
