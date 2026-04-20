package com.activepulse.agent.platform.macos;

import com.activepulse.agent.monitor.ActiveWindowInfo;
import com.activepulse.agent.monitor.ActiveWindowTracker;
import com.activepulse.agent.util.ProcessExec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * macOS active window via osascript (AppleScript).
 * Requires the app to be granted "Accessibility" permission under
 * System Settings → Privacy & Security → Accessibility for window title capture.
 * Process name works without any permission.
 */
public final class MacActiveWindowTracker implements ActiveWindowTracker {

    private static final Logger log = LoggerFactory.getLogger(MacActiveWindowTracker.class);

    private static final String SCRIPT =
            "global frontApp, frontAppName, windowTitle\n" +
            "set windowTitle to \"\"\n" +
            "tell application \"System Events\"\n" +
            "    set frontApp to first application process whose frontmost is true\n" +
            "    set frontAppName to name of frontApp\n" +
            "    try\n" +
            "        tell process frontAppName\n" +
            "            set windowTitle to name of front window\n" +
            "        end tell\n" +
            "    end try\n" +
            "end tell\n" +
            "return frontAppName & \"||\" & windowTitle\n";

    @Override
    public ActiveWindowInfo getActiveWindow() {
        try {
            var r = ProcessExec.run(3, "osascript", "-e", SCRIPT);
            if (!r.ok() || r.stdout().isBlank()) {
                return ActiveWindowInfo.unknown();
            }
            String[] parts = r.stdout().split("\\|\\|", 2);
            String proc  = parts.length > 0 ? parts[0].trim() : "unknown";
            String title = parts.length > 1 ? parts[1].trim() : "";
            return new ActiveWindowInfo(proc, title, "");
        } catch (Throwable t) {
            log.debug("macOS active window read failed: {}", t.getMessage());
            return ActiveWindowInfo.unknown();
        }
    }
}
