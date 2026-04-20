package com.activepulse.agent.autostart;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Windows autostart via HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Run.
 *
 * CRITICAL: We write to HKCU, not HKLM, because:
 *   - HKCU registers for the *currently logged-in user* (the AD user)
 *   - HKLM would require admin and would run for ALL users including service accounts
 *   - When jpackage installs to Program Files (machine-wide), the app still runs
 *     in the AD user's session — HKCU is the right place for their autostart.
 *
 * The app writes its own autostart entry on first launch. This means:
 *   1. Admin installs the .msi → app placed in C:\Program Files\ActivePulse\
 *   2. AD user logs in → launches the app manually (or via the Start Menu shortcut)
 *   3. App detects it's not in HKCU\...\Run and adds itself
 *   4. Future logins auto-start the app silently.
 */
public final class WindowsAutostartManager implements AutostartManager {

    private static final Logger log = LoggerFactory.getLogger(WindowsAutostartManager.class);
    private static final String RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String VALUE_NAME = "ActivePulseAgent";

    @Override
    public boolean install() {
        try {
            Path exe = resolveLauncher();
            if (exe == null) {
                log.warn("Cannot resolve launcher path; autostart not installed.");
                return false;
            }
            String cmd = "\"" + exe.toAbsolutePath() + "\"";
            Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME, cmd);
            log.info("Autostart registered at HKCU\\{}\\{} = {}", RUN_KEY, VALUE_NAME, cmd);
            return true;
        } catch (Throwable t) {
            log.error("Windows autostart install failed: {}", t.getMessage());
            return false;
        }
    }

    @Override
    public boolean uninstall() {
        try {
            if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME);
                log.info("Autostart entry removed.");
            }
            return true;
        } catch (Throwable t) {
            log.error("Windows autostart uninstall failed: {}", t.getMessage());
            return false;
        }
    }

    @Override
    public boolean isInstalled() {
        try {
            return Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Resolve the launcher to register. For jpackage installs, this is:
     *   C:\Program Files\ActivePulse\ActivePulse.exe
     *
     * Detection: walk up from the running JAR to find ActivePulse.exe.
     */
    private Path resolveLauncher() {
        try {
            Path jar = Paths.get(WindowsAutostartManager.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = jar.getParent();
            for (int i = 0; dir != null && i < 4; i++) {
                Path candidate = dir.resolve("ActivePulse.exe");
                if (java.nio.file.Files.isRegularFile(candidate)) return candidate;
                dir = dir.getParent();
            }
        } catch (Exception ignored) {}
        // Fallback — run the JAR directly via javaw
        String javaHome = System.getProperty("java.home");
        Path javaw = Paths.get(javaHome, "bin", "javaw.exe");
        try {
            Path jar = Paths.get(WindowsAutostartManager.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            // return null to indicate "no launcher exe", caller will warn.
            // We don't register javaw fallback automatically because it opens a window briefly.
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
