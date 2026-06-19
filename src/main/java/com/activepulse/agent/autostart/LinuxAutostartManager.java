package com.activepulse.agent.autostart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Linux autostart via XDG user autostart:
 *   ~/.config/autostart/activepulse.desktop
 *
 * This runs the app when the AD user logs into their desktop session,
 * not as root/system. Works with GNOME, KDE, XFCE.
 *
 * CHANGES FROM PREVIOUS VERSION:
 *   - Adds --watchdog argument to Exec line (matches Windows + macOS behavior)
 *   - Properly sets POSIX permissions (rw-r--r--, file isn't supposed to be executable)
 *   - Better launcher resolution: tries both /opt/activepulse/bin/ActivePulse AND
 *     resolves from the running JAR (for dev mode)
 *   - Verifies launcher exists before writing the .desktop file
 *   - More detailed logging
 *
 * Per-user scope: each AD user gets their own .desktop file on first login.
 * Agent calls install() on startup → idempotent (no-op if already present).
 */
public final class LinuxAutostartManager implements AutostartManager {

    private static final Logger log = LoggerFactory.getLogger(LinuxAutostartManager.class);
    private static final String DESKTOP_FILE_NAME = "activepulse.desktop";

    @Override
    public boolean install() {
        try {
            Path launcher = resolveLauncher();
            if (launcher == null) {
                log.warn("Cannot resolve Linux launcher path; autostart not installed.");
                return false;
            }

            Path desktop = desktopPath();
            Files.createDirectories(desktop.getParent());

            // Exec line uses --watchdog flag for kill-resistance, matching Windows + macOS
            String execLine = launcher.toAbsolutePath() + " --watchdog";

            String content = """
                [Desktop Entry]
                Type=Application
                Name=ActivePulse Agent
                Exec=%s
                Icon=activepulse
                Terminal=false
                X-GNOME-Autostart-enabled=true
                NoDisplay=true
                Hidden=false
                Comment=Background activity monitor
                Categories=Utility;
                """.formatted(execLine);

            Files.writeString(desktop, content, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            // .desktop files should be rw-r--r-- (NOT executable)
            try {
                Files.setPosixFilePermissions(desktop, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.OTHERS_READ));
            } catch (UnsupportedOperationException uoe) {
                // Non-POSIX filesystem — skip; file is still functional
            }

            log.info("Autostart desktop file written to {} (Exec={})", desktop, execLine);
            return true;
        } catch (Throwable t) {
            log.error("Linux autostart install failed: {}", t.getMessage());
            return false;
        }
    }

    @Override
    public boolean uninstall() {
        try {
            Path desktop = desktopPath();
            if (Files.deleteIfExists(desktop)) {
                log.info("Removed autostart desktop file: {}", desktop);
            } else {
                log.info("No autostart desktop file to remove.");
            }
            return true;
        } catch (Throwable t) {
            log.error("Linux autostart uninstall failed: {}", t.getMessage());
            return false;
        }
    }

    @Override
    public boolean isInstalled() {
        return Files.isRegularFile(desktopPath());
    }

    private Path desktopPath() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdg != null && !xdg.isBlank())
                ? Paths.get(xdg)
                : Paths.get(System.getProperty("user.home"), ".config");
        return base.resolve("autostart").resolve(DESKTOP_FILE_NAME);
    }

    /**
     * Resolves the ActivePulse launcher binary.
     *
     * Looks for it at the standard jpackage .deb install location first,
     * then walks up from the running JAR (for dev mode / non-standard installs).
     */
    private Path resolveLauncher() {
        // 1. Standard jpackage .deb install location
        Path standard = Paths.get("/opt/activepulse/bin/ActivePulse");
        if (Files.isRegularFile(standard) && Files.isExecutable(standard)) {
            log.info("Resolved launcher: {}", standard);
            return standard;
        }

        // 2. Walk up from the running JAR (dev mode fallback)
        try {
            Path jar = Paths.get(LinuxAutostartManager.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = jar.getParent();
            for (int i = 0; dir != null && i < 4; i++) {
                Path candidate = dir.resolve("ActivePulse");
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    log.info("Resolved launcher (dev): {}", candidate);
                    return candidate;
                }
                Path binCandidate = dir.resolve("bin").resolve("ActivePulse");
                if (Files.isRegularFile(binCandidate) && Files.isExecutable(binCandidate)) {
                    log.info("Resolved launcher (dev): {}", binCandidate);
                    return binCandidate;
                }
                dir = dir.getParent();
            }
        } catch (Exception e) {
            log.debug("resolveLauncher walk failed: {}", e.getMessage());
        }

        log.warn("Could not find ActivePulse launcher. Tried /opt/activepulse/bin/ActivePulse and JAR-relative paths.");
        return null;
    }
}

// package com.activepulse.agent.autostart;

// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

// import java.nio.file.*;

// /**
//  * Linux autostart via XDG user autostart:
//  *   ~/.config/autostart/activepulse.desktop
//  *
//  * This runs the app when the AD user logs into their desktop session,
//  * not as root/system. Works with GNOME, KDE, XFCE.
//  */
// public final class LinuxAutostartManager implements AutostartManager {

//     private static final Logger log = LoggerFactory.getLogger(LinuxAutostartManager.class);

//     @Override
//     public boolean install() {
//         try {
//             Path launcher = resolveLauncher();
//             if (launcher == null) {
//                 log.warn("Cannot resolve Linux launcher path.");
//                 return false;
//             }
//             Path desktop = desktopPath();
//             Files.createDirectories(desktop.getParent());
//             String content = """
//                 [Desktop Entry]
//                 Type=Application
//                 Name=ActivePulse Agent
//                 Exec=%s
//                 Icon=activepulse
//                 Terminal=false
//                 X-GNOME-Autostart-enabled=true
//                 NoDisplay=true
//                 Comment=Background activity monitor
//                 """.formatted(launcher.toAbsolutePath());
//             Files.writeString(desktop, content);
//             desktop.toFile().setExecutable(true);
//             log.info("Autostart desktop file written to {}", desktop);
//             return true;
//         } catch (Throwable t) {
//             log.error("Linux autostart install failed: {}", t.getMessage());
//             return false;
//         }
//     }

//     @Override
//     public boolean uninstall() {
//         try {
//             Files.deleteIfExists(desktopPath());
//             return true;
//         } catch (Throwable t) {
//             log.error("Linux autostart uninstall failed: {}", t.getMessage());
//             return false;
//         }
//     }

//     @Override
//     public boolean isInstalled() {
//         return Files.isRegularFile(desktopPath());
//     }

//     private Path desktopPath() {
//         String xdg = System.getenv("XDG_CONFIG_HOME");
//         Path base = (xdg != null && !xdg.isBlank())
//                 ? Paths.get(xdg)
//                 : Paths.get(System.getProperty("user.home"), ".config");
//         return base.resolve("autostart").resolve("activepulse.desktop");
//     }

//     private Path resolveLauncher() {
//         // For jpackage .deb installs, the launcher is:
//         //   /opt/activepulse/bin/ActivePulse
//         Path bin = Paths.get("/opt/activepulse/bin/ActivePulse");
//         return Files.isRegularFile(bin) ? bin : null;
//     }
// }
