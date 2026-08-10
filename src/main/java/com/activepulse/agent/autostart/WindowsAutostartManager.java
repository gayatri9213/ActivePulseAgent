package com.activepulse.agent.autostart;

import com.activepulse.agent.util.ProcessExec;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Windows autostart manager.
 *
 * IMPORTANT:
 * Windows Scheduled Task creation is handled by the elevated
 * ActivePulse deployment PowerShell script.
 *
 * This class intentionally does NOT create the Scheduled Task.
 *
 * The deployment script creates:
 *
 *      ActivePulseAgent
 *
 * with:
 *
 *      - AtLogOn trigger
 *      - ActivePulse.exe --no-watchdog
 *      - RestartOnFailure
 *      - MultipleInstances = IgnoreNew
 *
 * This Java class only:
 *
 *      - verifies the task
 *      - removes legacy HKLM Run entry
 *      - reports installation state
 *      - removes the task during uninstall
 *
 * This prevents Java startup from attempting to recreate or modify
 * the task every time ActivePulse starts.
 */
public final class WindowsAutostartManager implements AutostartManager {

    private static final Logger log =
            LoggerFactory.getLogger(WindowsAutostartManager.class);


    // =====================================================================
    // TASK NAME
    // =====================================================================

    /**
     * Permanent Windows Task Scheduler task created by the deployment
     * PowerShell script.
     */
    private static final String TASK_NAME =
            "ActivePulseAgent";


    // =====================================================================
    // LEGACY HKLM RUN KEY
    // =====================================================================

    /**
     * Old autostart mechanism used by previous ActivePulse versions.
     *
     * This is intentionally no longer used for autostart.
     */
    private static final WinReg.HKEY LEGACY_HIVE =
            WinReg.HKEY_LOCAL_MACHINE;

    private static final String LEGACY_RUN_KEY =
            "Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    private static final String LEGACY_VALUE_NAME =
            "ActivePulseAgent";


    // =====================================================================
    // INSTALL
    // =====================================================================

    @Override
    public boolean install() {

        try {

            log.info(
                    "Windows autostart is managed by the deployment " +
                            "Scheduled Task."
            );


            // -------------------------------------------------------------
            // 1. Remove legacy HKLM Run-key entry
            // -------------------------------------------------------------

            removeLegacyRunKey();


            // -------------------------------------------------------------
            // 2. Verify Scheduled Task
            // -------------------------------------------------------------

            if (isInstalled()) {

                log.info(
                        "Windows Scheduled Task '{}' already exists.",
                        TASK_NAME
                );

                log.info(
                        "Windows autostart installation verified successfully."
                );

                return true;
            }


            // -------------------------------------------------------------
            // Task does not exist
            // -------------------------------------------------------------

            log.warn(
                    "Windows Scheduled Task '{}' does not exist.",
                    TASK_NAME
            );

            log.warn(
                    "The ActivePulse deployment script must create the " +
                            "Scheduled Task with administrator privileges."
            );


            /*
             * IMPORTANT:
             *
             * We intentionally DO NOT create the task here.
             *
             * The deployment PowerShell script is the owner of the
             * Scheduled Task because it runs elevated.
             *
             * Returning false allows Main.java to report that the
             * autostart task was not installed.
             */

            return false;


        } catch (Throwable t) {

            log.error(
                    "Windows autostart verification failed: {}",
                    t.getMessage(),
                    t
            );

            return false;
        }
    }


    // =====================================================================
    // UNINSTALL
    // =====================================================================

    @Override
    public boolean uninstall() {

        boolean ok = true;


        // -------------------------------------------------------------
        // 1. Remove Scheduled Task
        // -------------------------------------------------------------

        try {

            log.info(
                    "Removing Windows Scheduled Task '{}'.",
                    TASK_NAME
            );


            ProcessExec.Result result =
                    ProcessExec.run(
                            20,
                            "schtasks",
                            "/Delete",
                            "/TN",
                            TASK_NAME,
                            "/F"
                    );


            if (result.ok()) {

                log.info(
                        "Windows Scheduled Task '{}' removed successfully.",
                        TASK_NAME
                );

            } else {

                String stderr =
                        result.stderr() == null
                                ? ""
                                : result.stderr().toLowerCase();


                /*
                 * Task already doesn't exist.
                 *
                 * This is not an uninstall failure.
                 */

                if (stderr.contains("cannot find")
                        || stderr.contains("cannot find the file")
                        || stderr.contains("does not exist")) {

                    log.info(
                            "Windows Scheduled Task '{}' was not present.",
                            TASK_NAME
                    );

                } else {

                    log.error(
                            "schtasks /Delete failed. Exit code: {}",
                            result.exitCode()
                    );

                    log.error(
                            "schtasks error: {}",
                            result.stderr()
                    );

                    ok = false;
                }
            }


        } catch (Throwable t) {

            log.error(
                    "Windows Scheduled Task removal failed: {}",
                    t.getMessage(),
                    t
            );

            ok = false;
        }


        // -------------------------------------------------------------
        // 2. Remove legacy HKLM Run key
        // -------------------------------------------------------------

        removeLegacyRunKey();


        return ok;
    }


    // =====================================================================
    // IS INSTALLED
    // =====================================================================

    @Override
    public boolean isInstalled() {

        try {

            ProcessExec.Result result =
                    ProcessExec.run(
                            15,
                            "schtasks",
                            "/Query",
                            "/TN",
                            TASK_NAME
                    );


            if (result.ok()) {

                log.debug(
                        "Windows Scheduled Task '{}' exists.",
                        TASK_NAME
                );

                return true;
            }


            log.debug(
                    "Windows Scheduled Task '{}' does not exist.",
                    TASK_NAME
            );

            return false;


        } catch (Throwable t) {

            log.debug(
                    "Unable to query Windows Scheduled Task '{}': {}",
                    TASK_NAME,
                    t.getMessage()
            );

            return false;
        }
    }


    // =====================================================================
    // REMOVE LEGACY HKLM RUN ENTRY
    // =====================================================================

    /**
     * Removes the old ActivePulse HKLM Run entry.
     *
     * Previous versions used:
     *
     * HKLM\Software\Microsoft\Windows\CurrentVersion\Run
     *
     * ActivePulseAgent = "...\ActivePulse.exe"
     *
     * The new deployment uses Task Scheduler instead.
     */
    private void removeLegacyRunKey() {

        try {

            if (!Advapi32Util.registryValueExists(
                    LEGACY_HIVE,
                    LEGACY_RUN_KEY,
                    LEGACY_VALUE_NAME
            )) {

                log.debug(
                        "Legacy HKLM Run entry '{}' does not exist.",
                        LEGACY_VALUE_NAME
                );

                return;
            }


            Advapi32Util.registryDeleteValue(
                    LEGACY_HIVE,
                    LEGACY_RUN_KEY,
                    LEGACY_VALUE_NAME
            );


            log.info(
                    "Removed legacy HKLM Run entry: " +
                            "HKLM\\{}\\{}",
                    LEGACY_RUN_KEY,
                    LEGACY_VALUE_NAME
            );


        } catch (Throwable t) {

            /*
             * Do not fail the whole application just because the
             * legacy registry entry cannot be removed.
             *
             * The Scheduled Task is the actual autostart mechanism.
             */

            log.warn(
                    "Unable to remove legacy HKLM Run entry: {}",
                    t.getMessage()
            );
        }
    }


    // =====================================================================
    // OPTIONAL: GET TASK STATUS
    // =====================================================================

    /**
     * Returns the current Task Scheduler status.
     *
     * Useful for diagnostics/logging.
     *
     * Example result:
     *
     *      Running
     *
     * or:
     *
     *      Ready
     *
     * or:
     *
     *      UNKNOWN
     */
    public String getTaskStatus() {

        try {

            ProcessExec.Result result =
                    ProcessExec.run(
                            15,
                            "schtasks",
                            "/Query",
                            "/TN",
                            TASK_NAME,
                            "/FO",
                            "LIST"
                    );


            if (!result.ok()) {

                return "NOT_FOUND";
            }


            String output =
                    result.stdout() == null
                            ? ""
                            : result.stdout();


            for (String line : output.split("\\R")) {

                String trimmed = line.trim();


                if (trimmed.regionMatches(
                        true,
                        0,
                        "Status:",
                        0,
                        "Status:".length()
                )) {

                    return trimmed.substring(
                            "Status:".length()
                    ).trim();
                }
            }


            return "UNKNOWN";


        } catch (Throwable t) {

            log.debug(
                    "Unable to get Scheduled Task status: {}",
                    t.getMessage()
            );

            return "UNKNOWN";
        }
    }


    // =====================================================================
    // OPTIONAL: START TASK
    // =====================================================================

    /**
     * Starts the already-installed Scheduled Task.
     *
     * This method does NOT create the task.
     *
     * It is useful if Main.java needs to make sure the deployment-created
     * task is currently running.
     */
    public boolean startTask() {

        try {

            if (!isInstalled()) {

                log.warn(
                        "Cannot start Scheduled Task '{}': task does not exist.",
                        TASK_NAME
                );

                return false;
            }


            ProcessExec.Result result =
                    ProcessExec.run(
                            20,
                            "schtasks",
                            "/Run",
                            "/TN",
                            TASK_NAME
                    );


            if (!result.ok()) {

                log.warn(
                        "Unable to start Scheduled Task '{}': {}",
                        TASK_NAME,
                        result.stderr()
                );

                return false;
            }


            log.info(
                    "Scheduled Task '{}' started.",
                    TASK_NAME
            );

            return true;


        } catch (Throwable t) {

            log.warn(
                    "Unable to start Scheduled Task '{}': {}",
                    TASK_NAME,
                    t.getMessage()
            );

            return false;
        }
    }


    // =====================================================================
    // OPTIONAL: STOP TASK
    // =====================================================================

    /**
     * Stops the currently running Scheduled Task.
     *
     * Normally this should NOT be called during normal agent operation.
     *
     * It is primarily useful during uninstall/maintenance.
     */
    public boolean stopTask() {

        try {

            if (!isInstalled()) {

                return true;
            }


            ProcessExec.Result result =
                    ProcessExec.run(
                            20,
                            "schtasks",
                            "/End",
                            "/TN",
                            TASK_NAME
                    );


            if (!result.ok()) {

                String stderr =
                        result.stderr() == null
                                ? ""
                                : result.stderr().toLowerCase();


                /*
                 * Already stopped is effectively success.
                 */

                if (stderr.contains("not running")
                        || stderr.contains("cannot find")
                        || stderr.contains("does not exist")) {

                    return true;
                }


                log.warn(
                        "Unable to stop Scheduled Task '{}': {}",
                        TASK_NAME,
                        result.stderr()
                );

                return false;
            }


            log.info(
                    "Scheduled Task '{}' stopped.",
                    TASK_NAME
            );

            return true;


        } catch (Throwable t) {

            log.warn(
                    "Unable to stop Scheduled Task '{}': {}",
                    TASK_NAME,
                    t.getMessage()
            );

            return false;
        }
    }


    // =====================================================================
    // GET LAUNCHER
    // =====================================================================

    /**
     * Resolves ActivePulse.exe.
     *
     * This is retained for diagnostics and compatibility.
     *
     * It does NOT create or register any autostart entry.
     */
    private Path resolveLauncher() {

        try {

            Path jar =
                    Paths.get(
                            WindowsAutostartManager.class
                                    .getProtectionDomain()
                                    .getCodeSource()
                                    .getLocation()
                                    .toURI()
                    );


            Path dir =
                    jar.getParent();


            /*
             * Example installation:
             *
             * C:\Program Files\ActivePulse\ActivePulse.exe
             *
             * The running JAR may be several directories below the
             * installation root.
             */

            for (
                    int i = 0;
                    dir != null && i < 6;
                    i++
            ) {

                Path candidate =
                        dir.resolve("ActivePulse.exe");


                if (Files.isRegularFile(candidate)) {

                    log.debug(
                            "Resolved ActivePulse launcher: {}",
                            candidate
                    );

                    return candidate;
                }


                dir =
                        dir.getParent();
            }


        } catch (Exception e) {

            log.debug(
                    "Unable to resolve ActivePulse.exe: {}",
                    e.getMessage()
            );
        }


        return null;
    }
}

//package com.activepulse.agent.autostart;
//
//import com.activepulse.agent.util.ProcessExec;
//import com.sun.jna.platform.win32.Advapi32Util;
//import com.sun.jna.platform.win32.WinReg;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//import java.nio.charset.StandardCharsets;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.nio.file.Paths;
//
///**
// * Windows autostart manager — registers a Task Scheduler task with a native
// * "restart on failure" policy, instead of the old HKLM Run-key + in-process
// * Watchdog child-spawn model.
// *
// * WHY THE CHANGE (v1.0.2):
// *   The old approach launched the Run-key entry with "--watchdog", which made
// *   Main.java spawn a *second* child process (the actual agent) supervised by
// *   the watchdog parent. That means two ActivePulse processes show up in
// *   Task Manager, and if the watchdog parent itself is killed, nothing brings
// *   the agent back until the next logon (see WatchdogMode's documented
// *   one-way termination semantics).
// *
// *   Task Scheduler's built-in RestartOnFailure setting gives us the same
// *   auto-restart guarantee that macOS already gets for free via launchd's
// *   KeepAlive (see MacAutostartManager) — but WITHOUT a second always-on
// *   process: Windows' own Task Scheduler engine (svchost, not us) notices the
// *   task ended and relaunches it. Task Manager only ever shows a single
// *   ActivePulse process at a time.
// *
// *   The launched process is started with "--no-watchdog" so it always runs
// *   as the agent directly — WatchdogMode.java is no longer used on the
// *   Task-Scheduler-managed Windows path (kept only for manual/dev testing).
// *
// * Task settings:
// *   - LogonTrigger (any user)          → matches previous Run-key behavior
// *   - Principal: Users group, interactive, least privilege
// *   - MultipleInstancesPolicy=IgnoreNew → prevents duplicate agents
// *   - RestartOnFailure: every 1 min, up to 999 times
// *   - ExecutionTimeLimit=PT0S           → never auto-killed for running "too long"
// *
// * TAMPER PROTECTION:
// *   schtasks.exe has no switch to set an explicit ACL at creation time, so
// *   right after registering the task we lock down its security descriptor
// *   via the Task Scheduler COM API (Schedule.Service), through a one-shot
// *   PowerShell call. The resulting DACL grants Full Control only to SYSTEM
// *   and Administrators; standard users get Read/Execute only — enough for
// *   the task to still *run* as the logged-in user (that's controlled by the
// *   Principal element, not the ACL), but NOT enough to Disable/Delete/Change
// *   it from Task Scheduler's UI, schtasks.exe, or PowerShell as a non-admin.
// *   Without this step, Task Scheduler's default inherited ACL can allow
// *   standard users to disable/delete tasks they can see.
// *
// * Requires admin privileges to register (same requirement as the old HKLM
// * write — the MSI already installs elevated).
// */
//public final class WindowsAutostartManager implements AutostartManager {
//
//    private static final Logger log = LoggerFactory.getLogger(WindowsAutostartManager.class);
//
//    // Legacy Run-key location — cleaned up on install() to avoid double-launch
//    // from a previous version of the agent.
//    private static final WinReg.HKEY LEGACY_HIVE = WinReg.HKEY_LOCAL_MACHINE;
//    private static final String LEGACY_RUN_KEY  = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
//    private static final String LEGACY_VALUE_NAME = "ActivePulseAgent";
//
//    private static final String TASK_NAME = "ActivePulseAgent";
//
//    // DACL: Full Control for SYSTEM + Administrators only. Authenticated Users /
//    // built-in Users get Generic Read + Generic Execute — they can see the task
//    // and it can still run under their session, but they cannot Disable, Delete,
//    // or reconfigure it. "P" = protected DACL (blocks inherited permissions from
//    // the parent folder from loosening this).
//    private static final String TAMPER_PROTECTED_SDDL =
//            "D:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;GRGX;;;BU)(A;;GRGX;;;AU)";
//
//    @Override
//    public boolean install() {
//        try {
//            Path exe = resolveLauncher();
//            if (exe == null) {
//                log.warn("Cannot resolve launcher path; autostart not installed.");
//                return false;
//            }
//
//            removeLegacyRunKey();
//
//            Path xmlFile = Files.createTempFile("activepulse-task-", ".xml");
//            try {
//                // schtasks/MSXML expects Windows-native UTF-16LE with a FF FE BOM.
//                // StandardCharsets.UTF_16 defaults to big-endian (FE FF BOM), which
//                // MSXML fails to parse ("task XML is malformed"). Write LE + BOM explicitly.
//                byte[] bom = {(byte) 0xFF, (byte) 0xFE};
//                byte[] body = buildTaskXml(exe.toAbsolutePath().toString())
//                        .getBytes(StandardCharsets.UTF_16LE);
//                byte[] fileBytes = new byte[bom.length + body.length];
//                System.arraycopy(bom, 0, fileBytes, 0, bom.length);
//                System.arraycopy(body, 0, fileBytes, bom.length, body.length);
//                Files.write(xmlFile, fileBytes);
//
//                ProcessExec.Result result = ProcessExec.run(30,
//                        "schtasks", "/Create", "/TN", TASK_NAME,
//                        "/XML", xmlFile.toAbsolutePath().toString(), "/F");
//
//                ProcessExec.Result runResult = ProcessExec.run(
//                        20,
//                        "schtasks",
//                        "/Run",
//                        "/TN",
//                        TASK_NAME);
//
//                if (!runResult.ok()) {
//                    log.warn("Unable to start Scheduled Task immediately: {}", runResult.stderr());
//                }
//
//                if (!result.ok()) {
//                    log.error("schtasks /Create failed (exit {}): {}", result.exitCode(), result.stderr());
//                    return false;
//                }
//                log.info("Scheduled Task '{}' registered with RestartOnFailure (1 minute): {}",
//                        TASK_NAME, exe);
//
//                if (!lockDownTaskAcl()) {
//                    log.warn("Task created but ACL lockdown failed — standard users may still be " +
//                            "able to disable/delete the '{}' task. Check that install() ran elevated.", TASK_NAME);
//                }
//                return true;
//            } finally {
//                Files.deleteIfExists(xmlFile);
//            }
//        } catch (Throwable t) {
//            log.error("Autostart install failed (likely insufficient privilege): {}", t.getMessage());
//            return false;
//        }
//    }
//
//    /**
//     * Restricts the task's security descriptor so only SYSTEM/Administrators
//     * can Disable, Delete, or reconfigure it. Standard users retain Read +
//     * Execute (required so Task Scheduler can still launch it under their
//     * session per the Principal element) but lose Write/Delete rights.
//     *
//     * schtasks.exe has no CLI switch for this, so it's done via the Task
//     * Scheduler COM API (Schedule.Service) through a single PowerShell call.
//     */
//    private boolean lockDownTaskAcl() {
//        String script = ("$s = New-Object -ComObject Schedule.Service; $s.Connect(); " +
//                "$f = $s.GetFolder('\\'); $t = $f.GetTask('%s'); " +
//                "$t.SetSecurityDescriptor('%s', 0)").formatted(TASK_NAME, TAMPER_PROTECTED_SDDL);
//
//        ProcessExec.Result result = ProcessExec.run(20,
//                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script);
//
//        if (!result.ok()) {
//            log.error("Task ACL lockdown failed (exit {}): {}", result.exitCode(), result.stderr());
//            return false;
//        }
//        log.info("Task '{}' locked down — only SYSTEM/Administrators can disable or delete it.", TASK_NAME);
//        return true;
//    }
//
//    @Override
//    public boolean uninstall() {
//        boolean ok = true;
//        try {
//            ProcessExec.Result result = ProcessExec.run(15,
//                    "schtasks", "/Delete", "/TN", TASK_NAME, "/F");
//            if (result.ok()) {
//                log.info("Scheduled Task '{}' removed.", TASK_NAME);
//            } else if (!result.stderr().toLowerCase().contains("cannot find")) {
//                log.error("schtasks /Delete failed (exit {}): {}", result.exitCode(), result.stderr());
//                ok = false;
//            }
//        } catch (Throwable t) {
//            log.error("Autostart uninstall failed: {}", t.getMessage());
//            ok = false;
//        }
//        removeLegacyRunKey();
//        return ok;
//    }
//
//    @Override
//    public boolean isInstalled() {
//        try {
//            ProcessExec.Result result = ProcessExec.run(15,
//                    "schtasks", "/Query", "/TN", TASK_NAME);
//            return result.ok();
//        } catch (Throwable t) {
//            return false;
//        }
//    }
//
//    /** Removes the old HKLM Run-key entry from pre-Task-Scheduler versions, if present. */
//    private void removeLegacyRunKey() {
//        try {
//            if (Advapi32Util.registryValueExists(LEGACY_HIVE, LEGACY_RUN_KEY, LEGACY_VALUE_NAME)) {
//                Advapi32Util.registryDeleteValue(LEGACY_HIVE, LEGACY_RUN_KEY, LEGACY_VALUE_NAME);
//                log.info("Removed legacy autostart entry at HKLM\\{}\\{}", LEGACY_RUN_KEY, LEGACY_VALUE_NAME);
//            }
//        } catch (Throwable t) {
//            log.debug("No legacy Run-key entry to remove ({})", t.getMessage());
//        }
//    }
//
//    /**
//     * Builds the Task Scheduler 2.0 XML definition. UTF-16 is required by
//     * schtasks /Create /XML.
//     */
//    private String buildTaskXml(String exePath) {
//        return """
//            <?xml version="1.0" encoding="UTF-16"?>
//            <Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
//              <RegistrationInfo>
//                <Description>ActivePulse background activity monitor. Restarts automatically if terminated.</Description>
//              </RegistrationInfo>
//              <Triggers>
//                <LogonTrigger>
//                  <Enabled>true</Enabled>
//                  <Repetition>
//                    <Interval>PT1M</Interval>
//                    <StopAtDurationEnd>false</StopAtDurationEnd>
//                  </Repetition>
//                </LogonTrigger>
//              </Triggers>
//              <Principals>
//                <Principal id="Author">
//                  <GroupId>S-1-5-32-545</GroupId>
//                  <RunLevel>LeastPrivilege</RunLevel>
//                </Principal>
//              </Principals>
//              <Settings>
//                <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
//                <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
//                <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
//                <AllowHardTerminate>false</AllowHardTerminate>
//                <StartWhenAvailable>true</StartWhenAvailable>
//                <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
//                <AllowStartOnDemand>true</AllowStartOnDemand>
//                <Enabled>true</Enabled>
//                <Hidden>false</Hidden>
//                <RunOnlyIfIdle>false</RunOnlyIfIdle>
//                <WakeToRun>false</WakeToRun>
//                <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
//                <Priority>7</Priority>
//                <DisallowStartOnRemoteAppSession>false</DisallowStartOnRemoteAppSession>
//                <RestartOnFailure>
//                    <Interval>PT1M</Interval>
//                    <Count>999</Count>
//                </RestartOnFailure>
//              </Settings>
//              <Actions Context="Author">
//                <Exec>
//                    <Command>"%s"</Command>
//                </Exec>
//              </Actions>
//            </Task>
//            """.formatted(exePath);
//    }
//
//    /**
//     * Resolves the launcher to register.
//     * For jpackage installs: C:\Program Files\ActivePulse\ActivePulse.exe
//     *
//     * Walks upward from the running JAR location to find the launcher.
//     */
//    private Path resolveLauncher() {
//        try {
//            Path jar = Paths.get(WindowsAutostartManager.class.getProtectionDomain()
//                    .getCodeSource().getLocation().toURI());
//            Path dir = jar.getParent();
//            for (int i = 0; dir != null && i < 4; i++) {
//                Path candidate = dir.resolve("ActivePulse.exe");
//                if (Files.isRegularFile(candidate)) {
//                    log.info("Resolved launcher: {}", candidate);
//                    return candidate;
//                }
//                dir = dir.getParent();
//            }
//            log.warn("Could not find ActivePulse.exe near JAR. Searched 4 levels up from JAR location.");
//        } catch (Exception e) {
//            log.debug("resolveLauncher error: {}", e.getMessage());
//        }
//        return null;
//    }
//}