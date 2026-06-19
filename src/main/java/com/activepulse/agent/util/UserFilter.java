package com.activepulse.agent.util;

import java.io.File;
import java.io.FileWriter;

/**
 * Determines whether the agent should run for the current OS user.
 *
 * Admin/system accounts (adcadmin, Administrator, SYSTEM, etc.) should NOT
 * have ActivePulse running — they're not monitoring targets, and creating
 * data folders for them just pollutes the filesystem.
 *
 * This check MUST be performed BEFORE any I/O (logback init, lock file
 * creation, DB connection). Otherwise the admin user ends up with stale
 * data folders that confuse subsequent runs.
 *
 * NOTE: Uses System.err for failure messages instead of SLF4J because
 * this class runs before logback is initialized.
 */
public final class UserFilter {

    private static final String DEFAULT_SKIP_USERS =
            "adcadmin,administrator,system,localsystem,defaultaccount,guest," +
            "wdagutilityaccount,root,daemon,admin,user";

    private UserFilter() {}

    /**
     * Returns true if the agent should EXIT instead of running for this user.
     * Reads SKIP_USERS and SKIP_ALL_ADMINS from EnvConfig (agent.env).
     */
    public static boolean shouldSkipCurrentUser() {
        String currentUser = System.getProperty("user.name", "").trim().toLowerCase();
        if (currentUser.isEmpty()) {
            return false; // Don't skip if we can't determine the user
        }

        // 1) Explicit username match
        String skipList = EnvConfig.get("SKIP_USERS", DEFAULT_SKIP_USERS).toLowerCase();
        for (String skip : skipList.split(",")) {
            String s = skip.trim()
                    .replace("'", "")    // strip stray quotes
                    .replace(".\\", "")  // strip ".\" prefix
                    .replace(".//", "");
            if (!s.isEmpty() && currentUser.equals(s)) {
                writeSkipMarker("matched SKIP_USERS entry: " + s);
                return true;
            }
        }

        // 2) Admin privilege detection (Windows only)
        if (EnvConfig.getBool("SKIP_ALL_ADMINS", true) && isWindowsAdmin()) {
            writeSkipMarker("user '" + currentUser + "' has admin privileges (SKIP_ALL_ADMINS=true)");
            return true;
        }

        return false;
    }

    /**
     * Cheap Windows admin detection: try to create a file in System32.
     * Only admins can write there. Returns false on non-Windows OSes.
     */
    private static boolean isWindowsAdmin() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("windows")) return false;

        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isBlank()) {
            systemRoot = "C:\\Windows";
        }

        File testFile = new File(systemRoot + "\\System32",
                "activepulse-admin-test-" + System.nanoTime() + ".tmp");
        try {
            if (testFile.createNewFile()) {
                testFile.delete();
                return true; // Wrote to System32 → admin
            }
        } catch (SecurityException | java.io.IOException ignored) {
            // Couldn't write → not admin
        }
        return false;
    }

    /**
     * Writes a single line to %TEMP%\activepulse-skip.log so support can see
     * WHY the agent isn't running on a given machine. Fire-and-forget.
     */
    private static void writeSkipMarker(String reason) {
        try {
            String tmpDir = System.getProperty("java.io.tmpdir");
            File marker = new File(tmpDir, "activepulse-skip.log");
            try (FileWriter w = new FileWriter(marker, true)) {
                w.write(java.time.Instant.now().toString());
                w.write(" - user=");
                w.write(System.getProperty("user.name", "?"));
                w.write(" - ");
                w.write(reason);
                w.write(System.lineSeparator());
            }
        } catch (Throwable ignored) {
            // Don't let logging failure prevent the skip itself
        }
    }
}