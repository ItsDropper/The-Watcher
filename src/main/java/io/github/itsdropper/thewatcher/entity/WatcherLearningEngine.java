package io.github.itsdropper.thewatcher.entity;

import net.minecraft.nbt.NbtCompound;

/**
 * A small, deterministic, offline learning model for the Watcher.
 *
 * It learns the player's typical gaze-on and gaze-off windows, then uses those
 * estimates to time hidden relocations. The model is intentionally bounded and
 * saved with the entity, so it requires neither a service nor an AI model.
 */
public final class WatcherLearningEngine {
    private static final double EMA_WEIGHT = 0.18D;
    private static final int MAX_RUN_TICKS = 72000;

    private double averageWatchedTicks = 100.0D;
    private double averageUnwatchedTicks = 36.0D;
    private double averagePlayerDistance = 48.0D;
    // Rises after repeated close encounters and slowly fades when the Watcher maintains distance.
    private double proximityPressure;
    private int currentRunTicks;
    private long observedTicks;
    private boolean hasPreviousState;
    private boolean previousWatched;

    public void observe(boolean watched, double distance) {
        if (observedTicks < Long.MAX_VALUE) {
            observedTicks++;
        }
        averagePlayerDistance = blend(averagePlayerDistance, clamp(distance, 0.0D, 512.0D));

        if (!hasPreviousState) {
            hasPreviousState = true;
            previousWatched = watched;
            currentRunTicks = 1;
            return;
        }

        if (watched == previousWatched) {
            currentRunTicks = Math.min(MAX_RUN_TICKS, currentRunTicks + 1);
            return;
        }

        if (previousWatched) {
            averageWatchedTicks = blend(averageWatchedTicks, currentRunTicks);
        } else {
            averageUnwatchedTicks = blend(averageUnwatchedTicks, currentRunTicks);
        }

        previousWatched = watched;
        currentRunTicks = 1;
    }

    /**
     * Aim for a relocation inside the player's learned usual gaze-off window.
     * The hard lower bound prevents twitchy, instantly repeated teleports.
     */
    public int getHiddenTeleportDelayTicks() {
        double learnedDelay = averageUnwatchedTicks * 0.42D;
        return (int) Math.round(clamp(learnedDelay, 12.0D, 48.0D));
    }

    /**
     * Adjust the base chance using learned gaze habits. Players who stare for
     * long periods and then quickly check away create a stronger opportunity.
     */
    public int adjustTeleportChance(int baseChance) {
        double gazePattern = averageWatchedTicks - averageUnwatchedTicks;
        int adjustment = (int) Math.round(clamp(gazePattern * 0.08D, -8.0D, 12.0D));
        return (int) clamp(baseChance + adjustment, 5.0D, 70.0D);
    }

    /**
     * Learn from personal-space violations during observing/stalking. This is an
     * intentionally slow signal: one close pass should not permanently change behavior.
     */
    public void observeStalkingDistance(double distance, int configuredChunks) {
        double baseline = WatcherStalkingDirector.minimumStalkingDistance(configuredChunks);
        if (distance < baseline) {
            proximityPressure += (1.0D - proximityPressure) * 0.0025D;
        } else {
            proximityPressure *= 0.999D;
        }
        proximityPressure = clamp(proximityPressure, 0.0D, 1.0D);
    }

    /**
     * Add a bounded offset to the normal stalking ring after repeated close encounters.
     * The director caps this offset to the available render-distance band.
     */
    public double getLearnedSafetyDistanceBonus(int configuredChunks) {
        double desiredBonus = proximityPressure * 12.0D;
        return WatcherStalkingDirector.clampLearnedOffset(configuredChunks, desiredBonus);
    }

    public double getProximityPressure() {
        return proximityPressure;
    }

    /**
     * Slightly changes the stalking phase length based on how close the player
     * tends to remain. It stays within a narrow range to avoid erratic pacing.
     */
    public int adjustStalkDuration(int baseTicks) {
        double distanceFactor = clamp(averagePlayerDistance / 48.0D, 0.75D, 1.25D);
        return (int) Math.round(baseTicks * distanceFactor);
    }

    public void writeToNbt(NbtCompound nbt) {
        nbt.putDouble("WatcherLearningAverageWatched", averageWatchedTicks);
        nbt.putDouble("WatcherLearningAverageUnwatched", averageUnwatchedTicks);
        nbt.putDouble("WatcherLearningAverageDistance", averagePlayerDistance);
        nbt.putDouble("WatcherLearningProximityPressure", proximityPressure);
        nbt.putInt("WatcherLearningCurrentRun", currentRunTicks);
        nbt.putLong("WatcherLearningObservedTicks", observedTicks);
        nbt.putBoolean("WatcherLearningHasPreviousState", hasPreviousState);
        nbt.putBoolean("WatcherLearningPreviousWatched", previousWatched);
    }

    public void readFromNbt(NbtCompound nbt) {
        if (nbt.contains("WatcherLearningAverageWatched")) {
            averageWatchedTicks = clamp(nbt.getDouble("WatcherLearningAverageWatched"), 1.0D, MAX_RUN_TICKS);
        }
        if (nbt.contains("WatcherLearningAverageUnwatched")) {
            averageUnwatchedTicks = clamp(nbt.getDouble("WatcherLearningAverageUnwatched"), 1.0D, MAX_RUN_TICKS);
        }
        if (nbt.contains("WatcherLearningAverageDistance")) {
            averagePlayerDistance = clamp(nbt.getDouble("WatcherLearningAverageDistance"), 0.0D, 512.0D);
        }
        if (nbt.contains("WatcherLearningProximityPressure")) {
            proximityPressure = clamp(nbt.getDouble("WatcherLearningProximityPressure"), 0.0D, 1.0D);
        }
        currentRunTicks = (int) clamp(nbt.getInt("WatcherLearningCurrentRun"), 0.0D, MAX_RUN_TICKS);
        observedTicks = Math.max(0L, nbt.getLong("WatcherLearningObservedTicks"));
        hasPreviousState = nbt.getBoolean("WatcherLearningHasPreviousState");
        previousWatched = nbt.getBoolean("WatcherLearningPreviousWatched");
    }

    private static double blend(double current, double sample) {
        return current + (sample - current) * EMA_WEIGHT;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
