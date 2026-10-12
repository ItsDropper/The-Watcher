package io.github.itsdropper.thewatcher;

/**
 * Client-selected options for the next single-player world created with The Watcher mode.
 * The common-side run manager reads these values in an integrated server.
 */
public final class WatcherWorldOptions {
    private static final int[] RUN_LENGTH_HOURS = {1, 3, 6, 12};

    public static volatile boolean WATCHER_MODE_ENABLED = false;
    public static volatile int RUN_LENGTH_INDEX = 2;

    private WatcherWorldOptions() {
    }

    public static int getRunLengthHours() {
        int index = Math.max(0, Math.min(RUN_LENGTH_INDEX, RUN_LENGTH_HOURS.length - 1));
        return RUN_LENGTH_HOURS[index];
    }

    public static void cycleRunLength() {
        RUN_LENGTH_INDEX = (RUN_LENGTH_INDEX + 1) % RUN_LENGTH_HOURS.length;
    }

    public static String getRunLengthLabel() {
        return getRunLengthHours() + (getRunLengthHours() == 1 ? " hour" : " hours");
    }
}
