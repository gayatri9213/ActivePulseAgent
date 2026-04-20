package com.activepulse.agent.autostart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;

/**
 * Linux autostart via XDG user autostart:
 *   ~/.config/autostart/activepulse.desktop
 *
 * This runs the app when the AD user logs into their desktop session,
 * not as root/system. Works with GNOME, KDE, XFCE.
 */
public final class LinuxAutostartManager implements AutostartManager {

    private static final Logger log = LoggerFactory.getLogger(LinuxAutostartManager.class);

    @Override
    public boolean install() {
        try {
            Path launcher = resolveLauncher();
            if (launcher == null) {
                log.warn("Cannot resolve Linux launcher path.");
                return false;
            }
            Path desktop = desktopPath();
            Files.createDirectories(desktop.getParent());
            String content = """
                [Desktop Entry]
                Type=Application
                Name=ActivePulse Agent
                Exec=%s
                Icon=activepulse
                Terminal=false
                X-GNOME-Autostart-enabled=true
                NoDisplay=true
                Comment=Background activity monitor
                """.formatted(launcher.toAbsolutePath());
            Files.writeString(desktop, content);
            desktop.toFile().setExecutable(true);
            log.info("Autostart desktop file written to {}", desktop);
            return true;
        } catch (Throwable t) {
            log.error("Linux autostart install failed: {}", t.getMessage());
            return false;
        }
    }

    @Override
    public boolean uninstall() {
        try {
            Files.deleteIfExists(desktopPath());
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
        return base.resolve("autostart").resolve("activepulse.desktop");
    }

    private Path resolveLauncher() {
        // For jpackage .deb installs, the launcher is:
        //   /opt/activepulse/bin/ActivePulse
        Path bin = Paths.get("/opt/activepulse/bin/ActivePulse");
        return Files.isRegularFile(bin) ? bin : null;
    }
}
