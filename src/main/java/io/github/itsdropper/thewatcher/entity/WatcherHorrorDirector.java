package io.github.itsdropper.thewatcher.entity;

/**
 * Pacing rules for subtle long-distance horror. The Watcher should create
 * uncertainty, not constantly announce itself with loud effects.
 */
public final class WatcherHorrorDirector {
    private WatcherHorrorDirector() {
    }

    public static boolean isDistantStalkingRange(double distance, int renderDistanceChunks) {
        double renderBlocks = WatcherStalkingDirector.renderDistanceBlocks(renderDistanceChunks);
        return distance > 20.0D
                && distance <= renderBlocks + 8.0D
                && distance >= WatcherStalkingDirector.minimumStalkingDistance(renderDistanceChunks) - 24.0D;
    }

    /**
     * Distant cues are intentionally rare. A cue can be rolled only after the
     * entity's shared dread cooldown expires, so it cannot become constant noise.
     */
    public static boolean shouldPlayDistantCue(double distance, int renderDistanceChunks, float roll) {
        return isDistantStalkingRange(distance, renderDistanceChunks) && roll < 0.16F;
    }

    public static int nextDistantCueCooldown(int randomTicks) {
        return 260 + Math.max(0, Math.min(220, randomTicks));
    }

    public static int nextCloseCueCooldown(int randomTicks) {
        return 100 + Math.max(0, Math.min(220, randomTicks));
    }
}
