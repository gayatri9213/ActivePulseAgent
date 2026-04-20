package com.activepulse.agent.platform.linux;

import com.activepulse.agent.monitor.ActiveWindowInfo;
import com.activepulse.agent.monitor.ActiveWindowTracker;
import com.activepulse.agent.util.ProcessExec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Linux active window via xdotool (X11). Wayland is not supported here;
 * most corporate/AD Linux setups still run X11 or XWayland.
 *
 * Requires: sudo apt-get install xdotool
 */
public final class LinuxActiveWindowTracker implements ActiveWindowTracker {

    private static final Logger log = LoggerFactory.getLogger(LinuxActiveWindowTracker.class);

    @Override
    public ActiveWindowInfo getActiveWindow() {
        try {
            // 1. Active window id
            var w = ProcessExec.run(2, "xdotool", "getactivewindow");
            if (!w.ok() || w.stdout().isBlank()) return ActiveWindowInfo.unknown();
            String wid = w.stdout().trim();

            // 2. Window title
            var t = ProcessExec.run(2, "xdotool", "getwindowname", wid);
            String title = t.ok() ? t.stdout().trim() : "";

            // 3. PID → process name
            var pidR = ProcessExec.run(2, "xdotool", "getwindowpid", wid);
            String procName = "unknown";
            if (pidR.ok() && !pidR.stdout().isBlank()) {
                String pid = pidR.stdout().trim();
                try {
                    String exe = Files.readSymbolicLink(Paths.get("/proc/" + pid + "/exe")).toString();
                    procName = Paths.get(exe).getFileName().toString();
                } catch (Exception ignored) {
                    var comm = ProcessExec.run(1, "cat", "/proc/" + pid + "/comm");
                    if (comm.ok()) procName = comm.stdout().trim();
                }
            }
            return new ActiveWindowInfo(procName, title, "");
        } catch (Throwable t) {
            log.debug("Linux active window read failed: {}", t.getMessage());
            return ActiveWindowInfo.unknown();
        }
    }
}
