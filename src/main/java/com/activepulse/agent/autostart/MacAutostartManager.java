package com.activepulse.agent.autostart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;

/**
 * macOS autostart via LaunchAgent at:
 *   ~/Library/LaunchAgents/com.aress.activepulse.plist  (per-user)
 *
 * For machine-wide (matches Windows HKLM behavior), see MacAutostartManagerSystem.
 *
 * CHANGES FROM PREVIOUS VERSION:
 *   - Adds --watchdog argument (matches Windows + Linux)
 *   - KeepAlive set to true (auto-restart if killed — equivalent to Windows watchdog
 *     and what your project needs for the "auto-restart on crash" requirement)
 *   - ThrottleInterval=10 (don't restart more than once per 10 seconds)
 *   - StandardOut/Error paths set so launchd logs are recoverable for debugging
 *   - ProcessType=Interactive (so the agent CAN see the user session — required
 *     for screenshot capture, keyboard hooks, etc. Background type would hide it)
 *   - Better launcher resolution
 *
 * Per-user scope: each AD user gets their own plist on first login.
 * Agent calls install() on startup → idempotent (no-op if already present).
 */
public final class MacAutostartManager implements AutostartManager {

    private static final Logger log = LoggerFactory.getLogger(MacAutostartManager.class);
    private static final String LABEL = "com.aress.activepulse";

    @Override
    public boolean install() {
        try {
            Path launcher = resolveLauncher();
            if (launcher == null) {
                log.warn("Cannot resolve macOS launcher path.");
                return false;
            }
            Path plist = plistPath();
            Files.createDirectories(plist.getParent());

            String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>Label</key>
                    <string>%s</string>
                    <key>ProgramArguments</key>
                    <array>
                        <string>%s</string>
                        <string>--watchdog</string>
                    </array>
                    <key>RunAtLoad</key>
                    <true/>
                    <key>KeepAlive</key>
                    <true/>
                    <key>ProcessType</key>
                    <string>Interactive</string>
                    <key>ThrottleInterval</key>
                    <integer>10</integer>
                    <key>StandardOutPath</key>
                    <string>/tmp/activepulse-stdout.log</string>
                    <key>StandardErrorPath</key>
                    <string>/tmp/activepulse-stderr.log</string>
                </dict>
                </plist>
                """.formatted(LABEL, launcher.toAbsolutePath());

            Files.writeString(plist, xml, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            log.info("LaunchAgent written to {} (Program={} --watchdog)", plist, launcher);
            return true;
        } catch (Throwable t) {
            log.error("macOS autostart install failed: {}", t.getMessage());
            return false;
        }
    }

    @Override
    public boolean uninstall() {
        try {
            Path plist = plistPath();
            if (Files.deleteIfExists(plist)) {
                log.info("Removed LaunchAgent: {}", plist);
            } else {
                log.info("No LaunchAgent to remove.");
            }
            return true;
        } catch (Throwable t) {
            log.error("macOS autostart uninstall failed: {}", t.getMessage());
            return false;
        }
    }

    @Override
    public boolean isInstalled() {
        return Files.isRegularFile(plistPath());
    }

    private Path plistPath() {
        return Paths.get(System.getProperty("user.home"),
                "Library", "LaunchAgents", LABEL + ".plist");
    }

    /**
     * Resolves the ActivePulse launcher.
     * Standard jpackage .dmg installs it to:
     *   /Applications/ActivePulse.app/Contents/MacOS/ActivePulse
     */
    private Path resolveLauncher() {
        Path standard = Paths.get("/Applications/ActivePulse.app/Contents/MacOS/ActivePulse");
        if (Files.isRegularFile(standard) && Files.isExecutable(standard)) {
            log.info("Resolved launcher: {}", standard);
            return standard;
        }

        // Dev mode fallback: walk up from the JAR
        try {
            Path jar = Paths.get(MacAutostartManager.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = jar.getParent();
            for (int i = 0; dir != null && i < 5; i++) {
                Path macOsDir = dir.resolve("Contents").resolve("MacOS").resolve("ActivePulse");
                if (Files.isRegularFile(macOsDir) && Files.isExecutable(macOsDir)) {
                    log.info("Resolved launcher (dev): {}", macOsDir);
                    return macOsDir;
                }
                dir = dir.getParent();
            }
        } catch (Exception e) {
            log.debug("resolveLauncher walk failed: {}", e.getMessage());
        }

        log.warn("Could not find ActivePulse launcher at /Applications/ActivePulse.app");
        return null;
    }
}

// package com.activepulse.agent.autostart;

// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

// import java.nio.file.*;

// /**
//  * macOS autostart via user LaunchAgent at:
//  *   ~/Library/LaunchAgents/com.aress.activepulse.plist
//  *
//  * User-level agent (not /Library/LaunchDaemons) so it runs as the AD user
//  * after login, not as root.
//  */
// public final class MacAutostartManager implements AutostartManager {

//     private static final Logger log = LoggerFactory.getLogger(MacAutostartManager.class);
//     private static final String LABEL = "com.aress.activepulse";

//     @Override
//     public boolean install() {
//         try {
//             Path launcher = resolveLauncher();
//             if (launcher == null) {
//                 log.warn("Cannot resolve macOS launcher path.");
//                 return false;
//             }
//             Path plist = plistPath();
//             Files.createDirectories(plist.getParent());
//             String xml = """
//                 <?xml version="1.0" encoding="UTF-8"?>
//                 <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
//                 <plist version="1.0">
//                 <dict>
//                     <key>Label</key>                 <string>%s</string>
//                     <key>ProgramArguments</key>
//                     <array>
//                         <string>%s</string>
//                     </array>
//                     <key>RunAtLoad</key>             <true/>
//                     <key>KeepAlive</key>             <false/>
//                     <key>ProcessType</key>           <string>Background</string>
//                 </dict>
//                 </plist>
//                 """.formatted(LABEL, launcher.toAbsolutePath());
//             Files.writeString(plist, xml);
//             log.info("LaunchAgent written to {}", plist);
//             return true;
//         } catch (Throwable t) {
//             log.error("macOS autostart install failed: {}", t.getMessage());
//             return false;
//         }
//     }

//     @Override
//     public boolean uninstall() {
//         try {
//             Files.deleteIfExists(plistPath());
//             return true;
//         } catch (Throwable t) {
//             log.error("macOS autostart uninstall failed: {}", t.getMessage());
//             return false;
//         }
//     }

//     @Override
//     public boolean isInstalled() {
//         return Files.isRegularFile(plistPath());
//     }

//     private Path plistPath() {
//         return Paths.get(System.getProperty("user.home"),
//                 "Library", "LaunchAgents", LABEL + ".plist");
//     }

//     private Path resolveLauncher() {
//         // For jpackage .pkg installs, the launcher is typically:
//         //   /Applications/ActivePulse.app/Contents/MacOS/ActivePulse
//         Path app = Paths.get("/Applications/ActivePulse.app/Contents/MacOS/ActivePulse");
//         return Files.isRegularFile(app) ? app : null;
//     }
// }
