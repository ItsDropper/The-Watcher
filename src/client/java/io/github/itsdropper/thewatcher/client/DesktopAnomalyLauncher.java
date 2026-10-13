package io.github.itsdropper.thewatcher.client;

import io.github.itsdropper.thewatcher.TheWatcher;
import net.minecraft.util.math.random.Random;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * Opens a real Windows desktop MessageBox from a separate PowerShell process.
 * This is a fictional game effect, not a Windows-generated security/system alert.
 */
public final class DesktopAnomalyLauncher {
    private static final String[] MESSAGES = {
            "A window was observed outside the game.\r\n\r\nThe source could not be identified.",
            "The Watcher has registered a second observer.\r\n\r\nNo matching window was found.",
            "A background response arrived before the request.\r\n\r\nThe event has been recorded.",
            "The active window changed while no input was received.\r\n\r\nThis event has no known source."
    };

    private static Process activePopup;

    private DesktopAnomalyLauncher() {}

    public static synchronized void showRandomPopup(Random random) {
        if (!isWindows()) return;
        if (activePopup != null && activePopup.isAlive()) return;

        String message = MESSAGES[random.nextInt(MESSAGES.length)];
        // Fixed script + Base64 UTF-16LE avoids passing message text as shell syntax.
        String script = "Add-Type -AssemblyName System.Windows.Forms; "
                + "[System.Windows.Forms.MessageBox]::Show("
                + "'" + message.replace("'", "''") + "', "
                + "'The Watcher', "
                + "[System.Windows.Forms.MessageBoxButtons]::OK, "
                + "[System.Windows.Forms.MessageBoxIcon]::Warning) | Out-Null";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));

        try {
            activePopup = new ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-WindowStyle", "Hidden",
                    "-EncodedCommand", encoded)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (Exception exception) {
            TheWatcher.LOGGER.warn("Could not open the optional native desktop horror dialog.", exception);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
