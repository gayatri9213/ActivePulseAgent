package com.activepulse.agent.autostart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;

/**
 * macOS autostart via user LaunchAgent at:
 *   ~/Library/LaunchAgents/com.aress.activepulse.plist
 *
 * User-level agent (not /Library/LaunchDaemons) so it runs as the AD user
 * after login, not as root.
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
                    <key>Label</key>                 <string>%s</string>
                    <key>ProgramArguments</key>
                    <array>
                        <string>%s</string>
                    </array>
                    <key>RunAtLoad</key>             <true/>
                    <key>KeepAlive</key>             <false/>
                    <key>ProcessType</key>           <string>Background</string>
                </dict>
                </plist>
                """.formatted(LABEL, launcher.toAbsolutePath());
            Files.writeString(plist, xml);
            log.info("LaunchAgent written to {}", plist);
            return true;
        } catch (Throwable t) {
            log.error("macOS autostart install failed: {}", t.getMessage());
            return false;
        }
    }

    @Override
    public boolean uninstall() {
        try {
            Files.deleteIfExists(plistPath());
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

    private Path resolveLauncher() {
        // For jpackage .pkg installs, the launcher is typically:
        //   /Applications/ActivePulse.app/Contents/MacOS/ActivePulse
        Path app = Paths.get("/Applications/ActivePulse.app/Contents/MacOS/ActivePulse");
        return Files.isRegularFile(app) ? app : null;
    }
}
