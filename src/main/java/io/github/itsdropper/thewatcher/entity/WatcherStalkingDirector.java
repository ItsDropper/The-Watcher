package io.github.itsdropper.thewatcher.entity;

/**
 * Spatial policy for long-range stalking. The Watcher stays far enough away
 * to be unsettling, but close enough that players can actually spot it.
 */
public final class WatcherStalkingDirector {
    private static final int MIN_RENDER_CHUNKS = 4;
    private static final double MINIMUM_STALK_DISTANCE = 30.0D;

    private WatcherStalkingDirector() {
    }

    public static int renderDistanceChunks(int configuredChunks) {
        return Math.max(MIN_RENDER_CHUNKS, Math.min(64, configuredChunks));
    }

    public static double renderDistanceBlocks(int configuredChunks) {
        return renderDistanceChunks(configuredChunks) * 16.0D;
    }

    /**
     * Use a closer, wider stalking band. At 12 chunks this puts the Watcher
     * roughly 73-97 blocks away instead of hiding at the render-distance edge.
     */
    public static double minimumStalkingDistance(int configuredChunks) {
        return Math.max(MINIMUM_STALK_DISTANCE,
                Math.min(76.0D, renderDistanceBlocks(configuredChunks) * 0.38D));
    }

    public static double maximumStalkingDistance(int configuredChunks) {
        double renderLimit = Math.max(0.0D, renderDistanceBlocks(configuredChunks) - 8.0D);
        return Math.max(minimumStalkingDistance(configuredChunks) + 8.0D,
                Math.min(renderLimit, minimumStalkingDistance(configuredChunks) + 24.0D));
    }

    public static boolean isTooCloseForStalking(double distance, int configuredChunks) {
        return distance < minimumStalkingDistance(configuredChunks);
    }

    public static double clampLearnedOffset(int configuredChunks, double requestedOffset) {
        double renderBlocks = renderDistanceBlocks(configuredChunks);
        double availableOffset = Math.max(0.0D, renderBlocks - 2.0D
                - maximumStalkingDistance(configuredChunks));
        return Math.max(0.0D, Math.min(Math.min(12.0D, availableOffset), requestedOffset));
    }

    public static double candidateRadius(int configuredChunks, double unitSample) {
        return candidateRadius(configuredChunks, unitSample, 0.0D);
    }

    public static double candidateRadius(int configuredChunks, double unitSample, double learnedOffset) {
        double sample = Math.max(0.0D, Math.min(0.999999D, unitSample));
        double offset = clampLearnedOffset(configuredChunks, learnedOffset);
        double min = minimumStalkingDistance(configuredChunks) + offset;
        double max = maximumStalkingDistance(configuredChunks) + offset;
        return min + (max - min) * sample;
    }

    public static double targetRange(int configuredChunks) {
        return renderDistanceBlocks(configuredChunks) + 48.0D;
    }
}