package com.activepulse.agent.autostart;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Windows autostart manager — writes to HKLM\...\Run via JNA.
 *
 * KEY CHANGES FROM PREVIOUS VERSION:
 *   - Writes to HKLM (machine-wide) instead of HKCU (per-user).
 *     This matches the silent-install requirements doc and the existing
 *     deployment workflow where admin installs the .msi and agent runs
 *     for whichever user signs in.
 *   - Uses JNA Advapi32Util — no external `reg.exe` calls, no `cmd.exe`,
 *     no PowerShell. In-process Win32 API calls don't trigger AV.
 *   - --watchdog flag is appended automatically since the jpackage launcher
 *     bakes it in (see build-installers.yml --arguments "--watchdog").
 *     Including it here too is defensive — harmless if duplicated.
 *
 * Requires admin privileges to write to HKLM.
 * The agent installer (MSI) runs as admin so the install-time call works.
 * If called from a non-admin user context, the write will fail silently
 * and the agent logs the failure.
 *
 * Why not just use the MSI's built-in registry-write feature?
 *   - jpackage's MSI doesn't expose post-install registry actions
 *   - This way the agent owns its autostart configuration in code
 *   - Lets us update behavior in a future version without rebuilding MSI
 *   - Works for non-MSI installs (dev/test runs)
 */
public final class WindowsAutostartManager implements AutostartManager {

    private static final Logger log = LoggerFactory.getLogger(WindowsAutostartManager.class);

    private static final WinReg.HKEY HIVE = WinReg.HKEY_LOCAL_MACHINE;
    private static final String RUN_KEY  = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String VALUE_NAME = "ActivePulseAgent";

    @Override
    public boolean install() {
        try {
            Path exe = resolveLauncher();
            if (exe == null) {
                log.warn("Cannot resolve launcher path; autostart not installed.");
                return false;
            }
            // Quote the path in case "Program Files" has a space.
            // --watchdog flag is also baked into the launcher itself, so this is belt-and-suspenders.
            String cmd = "\"" + exe.toAbsolutePath() + "\" --watchdog";

            Advapi32Util.registrySetStringValue(HIVE, RUN_KEY, VALUE_NAME, cmd);
            log.info("Autostart registered at HKLM\\{}\\{} = {}", RUN_KEY, VALUE_NAME, cmd);
            return true;
        } catch (Throwable t) {
            log.error("Autostart install failed (likely insufficient privilege to write HKLM): {}",
                    t.getMessage());
            return false;
        }
    }

    @Override
    public boolean uninstall() {
        try {
            if (Advapi32Util.registryValueExists(HIVE, RUN_KEY, VALUE_NAME)) {
                Advapi32Util.registryDeleteValue(HIVE, RUN_KEY, VALUE_NAME);
                log.info("Autostart entry removed from HKLM\\{}\\{}", RUN_KEY, VALUE_NAME);
            } else {
                log.info("No autostart entry to remove.");
            }
            return true;
        } catch (Throwable t) {
            log.error("Autostart uninstall failed: {}", t.getMessage());
            return false;
        }
    }

    @Override
    public boolean isInstalled() {
        try {
            return Advapi32Util.registryValueExists(HIVE, RUN_KEY, VALUE_NAME);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Resolves the launcher to register.
     * For jpackage installs: C:\Program Files\ActivePulse\ActivePulse.exe
     *
     * Walks upward from the running JAR location to find the launcher.
     */
    private Path resolveLauncher() {
        try {
            Path jar = Paths.get(WindowsAutostartManager.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = jar.getParent();
            for (int i = 0; dir != null && i < 4; i++) {
                Path candidate = dir.resolve("ActivePulse.exe");
                if (Files.isRegularFile(candidate)) {
                    log.info("Resolved launcher: {}", candidate);
                    return candidate;
                }
                dir = dir.getParent();
            }
            log.warn("Could not find ActivePulse.exe near JAR. Searched 4 levels up from JAR location.");
        } catch (Exception e) {
            log.debug("resolveLauncher error: {}", e.getMessage());
        }
        return null;
    }
}