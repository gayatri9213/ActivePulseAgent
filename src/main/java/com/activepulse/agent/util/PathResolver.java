package com.activepulse.agent.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves per-user writable directories for data, logs, and screenshots.
 * For a machine-wide install, every AD user gets their own directory under
 * their profile — NEVER under ProgramData or /Applications, which require admin.
 *
 *   Windows: C:\Users\<user>\AppData\Local\ActivePulse\
 *   macOS:   ~/Library/Application Support/ActivePulse/
 *   Linux:   ~/.local/share/activepulse/
 */
public final class PathResolver {

    private PathResolver() {}

    public static Path dataDir() {
        String override = EnvConfig.get("DATA_DIR", "");
        if (!override.isBlank()) {
            return ensure(Paths.get(override));
        }

        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home");

        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            Path base = (localAppData != null && !localAppData.isBlank())
                    ? Paths.get(localAppData)
                    : Paths.get(home, "AppData", "Local");
            return ensure(base.resolve("ActivePulse"));
        }
        if (os.contains("mac")) {
            return ensure(Paths.get(home, "Library", "Application Support", "ActivePulse"));
        }
        // Linux / others
        String xdgData = System.getenv("XDG_DATA_HOME");
        Path base = (xdgData != null && !xdgData.isBlank())
                ? Paths.get(xdgData)
                : Paths.get(home, ".local", "share");
        return ensure(base.resolve("activepulse"));
    }

    public static Path logsDir() {
        return ensure(dataDir().resolve("logs"));
    }

    public static Path screenshotsDir() {
        return ensure(dataDir().resolve("screenshots"));
    }

    public static Path databaseFile() {
        return dataDir().resolve("activepulse.db");
    }

    public static Path lockFile() {
        return dataDir().resolve("activepulse.lock");
    }

    private static Path ensure(Path p) {
        try { Files.createDirectories(p); } catch (Exception ignored) {}
        return p;
    }
}
