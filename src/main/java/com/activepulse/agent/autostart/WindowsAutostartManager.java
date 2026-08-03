package com.activepulse.agent.autostart;

import com.activepulse.agent.util.ProcessExec;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Windows autostart manager — registers a Task Scheduler task with a native
 * "restart on failure" policy, instead of the old HKLM Run-key + in-process
 * Watchdog child-spawn model.
 *
 * WHY THE CHANGE (v1.0.2):
 *   The old approach launched the Run-key entry with "--watchdog", which made
 *   Main.java spawn a *second* child process (the actual agent) supervised by
 *   the watchdog parent. That means two ActivePulse processes show up in
 *   Task Manager, and if the watchdog parent itself is killed, nothing brings
 *   the agent back until the next logon (see WatchdogMode's documented
 *   one-way termination semantics).
 *
 *   Task Scheduler's built-in RestartOnFailure setting gives us the same
 *   auto-restart guarantee that macOS already gets for free via launchd's
 *   KeepAlive (see MacAutostartManager) — but WITHOUT a second always-on
 *   process: Windows' own Task Scheduler engine (svchost, not us) notices the
 *   task ended and relaunches it. Task Manager only ever shows a single
 *   ActivePulse process at a time.
 *
 *   The launched process is started with "--no-watchdog" so it always runs
 *   as the agent directly — WatchdogMode.java is no longer used on the
 *   Task-Scheduler-managed Windows path (kept only for manual/dev testing).
 *
 * Task settings:
 *   - LogonTrigger (any user)          → matches previous Run-key behavior
 *   - Principal: Users group, interactive, least privilege
 *   - MultipleInstancesPolicy=IgnoreNew → prevents duplicate agents
 *   - RestartOnFailure: every 1 min, up to 999 times
 *   - ExecutionTimeLimit=PT0S           → never auto-killed for running "too long"
 *
 * TAMPER PROTECTION:
 *   schtasks.exe has no switch to set an explicit ACL at creation time, so
 *   right after registering the task we lock down its security descriptor
 *   via the Task Scheduler COM API (Schedule.Service), through a one-shot
 *   PowerShell call. The resulting DACL grants Full Control only to SYSTEM
 *   and Administrators; standard users get Read/Execute only — enough for
 *   the task to still *run* as the logged-in user (that's controlled by the
 *   Principal element, not the ACL), but NOT enough to Disable/Delete/Change
 *   it from Task Scheduler's UI, schtasks.exe, or PowerShell as a non-admin.
 *   Without this step, Task Scheduler's default inherited ACL can allow
 *   standard users to disable/delete tasks they can see.
 *
 * Requires admin privileges to register (same requirement as the old HKLM
 * write — the MSI already installs elevated).
 */
public final class WindowsAutostartManager implements AutostartManager {

    private static final Logger log = LoggerFactory.getLogger(WindowsAutostartManager.class);

    // Legacy Run-key location — cleaned up on install() to avoid double-launch
    // from a previous version of the agent.
    private static final WinReg.HKEY LEGACY_HIVE = WinReg.HKEY_LOCAL_MACHINE;
    private static final String LEGACY_RUN_KEY  = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String LEGACY_VALUE_NAME = "ActivePulseAgent";

    private static final String TASK_NAME = "ActivePulseAgent";

    // DACL: Full Control for SYSTEM + Administrators only. Authenticated Users /
    // built-in Users get Generic Read + Generic Execute — they can see the task
    // and it can still run under their session, but they cannot Disable, Delete,
    // or reconfigure it. "P" = protected DACL (blocks inherited permissions from
    // the parent folder from loosening this).
    private static final String TAMPER_PROTECTED_SDDL =
            "D:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;GRGX;;;BU)(A;;GRGX;;;AU)";

    @Override
    public boolean install() {
        try {
            Path exe = resolveLauncher();
            if (exe == null) {
                log.warn("Cannot resolve launcher path; autostart not installed.");
                return false;
            }

            removeLegacyRunKey();

            Path xmlFile = Files.createTempFile("activepulse-task-", ".xml");
            try {
                // schtasks/MSXML expects Windows-native UTF-16LE with a FF FE BOM.
                // StandardCharsets.UTF_16 defaults to big-endian (FE FF BOM), which
                // MSXML fails to parse ("task XML is malformed"). Write LE + BOM explicitly.
                byte[] bom = {(byte) 0xFF, (byte) 0xFE};
                byte[] body = buildTaskXml(exe.toAbsolutePath().toString())
                        .getBytes(StandardCharsets.UTF_16LE);
                byte[] fileBytes = new byte[bom.length + body.length];
                System.arraycopy(bom, 0, fileBytes, 0, bom.length);
                System.arraycopy(body, 0, fileBytes, bom.length, body.length);
                Files.write(xmlFile, fileBytes);

                ProcessExec.Result result = ProcessExec.run(30,
                        "schtasks", "/Create", "/TN", TASK_NAME,
                        "/XML", xmlFile.toAbsolutePath().toString(), "/F");

                if (!result.ok()) {
                    log.error("schtasks /Create failed (exit {}): {}", result.exitCode(), result.stderr());
                    return false;
                }
                log.info("Scheduled Task '{}' registered (restart-on-failure, single process): {} --no-watchdog",
                        TASK_NAME, exe);

                if (!lockDownTaskAcl()) {
                    log.warn("Task created but ACL lockdown failed — standard users may still be " +
                            "able to disable/delete the '{}' task. Check that install() ran elevated.", TASK_NAME);
                }
                return true;
            } finally {
                Files.deleteIfExists(xmlFile);
            }
        } catch (Throwable t) {
            log.error("Autostart install failed (likely insufficient privilege): {}", t.getMessage());
            return false;
        }
    }

    /**
     * Restricts the task's security descriptor so only SYSTEM/Administrators
     * can Disable, Delete, or reconfigure it. Standard users retain Read +
     * Execute (required so Task Scheduler can still launch it under their
     * session per the Principal element) but lose Write/Delete rights.
     *
     * schtasks.exe has no CLI switch for this, so it's done via the Task
     * Scheduler COM API (Schedule.Service) through a single PowerShell call.
     */
    private boolean lockDownTaskAcl() {
        String script = ("$s = New-Object -ComObject Schedule.Service; $s.Connect(); " +
                "$f = $s.GetFolder('\\'); $t = $f.GetTask('%s'); " +
                "$t.SetSecurityDescriptor('%s', 0)").formatted(TASK_NAME, TAMPER_PROTECTED_SDDL);

        ProcessExec.Result result = ProcessExec.run(20,
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script);

        if (!result.ok()) {
            log.error("Task ACL lockdown failed (exit {}): {}", result.exitCode(), result.stderr());
            return false;
        }
        log.info("Task '{}' locked down — only SYSTEM/Administrators can disable or delete it.", TASK_NAME);
        return true;
    }

    @Override
    public boolean uninstall() {
        boolean ok = true;
        try {
            ProcessExec.Result result = ProcessExec.run(15,
                    "schtasks", "/Delete", "/TN", TASK_NAME, "/F");
            if (result.ok()) {
                log.info("Scheduled Task '{}' removed.", TASK_NAME);
            } else if (!result.stderr().toLowerCase().contains("cannot find")) {
                log.error("schtasks /Delete failed (exit {}): {}", result.exitCode(), result.stderr());
                ok = false;
            }
        } catch (Throwable t) {
            log.error("Autostart uninstall failed: {}", t.getMessage());
            ok = false;
        }
        removeLegacyRunKey();
        return ok;
    }

    @Override
    public boolean isInstalled() {
        try {
            ProcessExec.Result result = ProcessExec.run(15,
                    "schtasks", "/Query", "/TN", TASK_NAME);
            return result.ok();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Removes the old HKLM Run-key entry from pre-Task-Scheduler versions, if present. */
    private void removeLegacyRunKey() {
        try {
            if (Advapi32Util.registryValueExists(LEGACY_HIVE, LEGACY_RUN_KEY, LEGACY_VALUE_NAME)) {
                Advapi32Util.registryDeleteValue(LEGACY_HIVE, LEGACY_RUN_KEY, LEGACY_VALUE_NAME);
                log.info("Removed legacy autostart entry at HKLM\\{}\\{}", LEGACY_RUN_KEY, LEGACY_VALUE_NAME);
            }
        } catch (Throwable t) {
            log.debug("No legacy Run-key entry to remove ({})", t.getMessage());
        }
    }

    /**
     * Builds the Task Scheduler 2.0 XML definition. UTF-16 is required by
     * schtasks /Create /XML.
     */
    private String buildTaskXml(String exePath) {
        return """
            <?xml version="1.0" encoding="UTF-16"?>
            <Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
              <RegistrationInfo>
                <Description>ActivePulse background activity monitor. Restarts automatically if terminated.</Description>
              </RegistrationInfo>
              <Triggers>
                <LogonTrigger>
                  <Enabled>true</Enabled>
                  <Repetition>
                    <Interval>PT1M</Interval>
                    <StopAtDurationEnd>false</StopAtDurationEnd>
                  </Repetition>
                </LogonTrigger>
              </Triggers>
              <Principals>
                <Principal id="Author">
                  <GroupId>S-1-5-32-545</GroupId>
                  <RunLevel>LeastPrivilege</RunLevel>
                </Principal>
              </Principals>
              <Settings>
                <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
                <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
                <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
                <AllowHardTerminate>true</AllowHardTerminate>
                <StartWhenAvailable>true</StartWhenAvailable>
                <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
                <AllowStartOnDemand>true</AllowStartOnDemand>
                <Enabled>true</Enabled>
                <Hidden>false</Hidden>
                <RunOnlyIfIdle>false</RunOnlyIfIdle>
                <WakeToRun>false</WakeToRun>
                <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
                <Priority>7</Priority>
                <RestartOnFailure>
                  <Interval>PT1M</Interval>
                  <Count>999</Count>
                </RestartOnFailure>
              </Settings>
              <Actions Context="Author">
                <Exec>
                  <Command>"%s"</Command>
                  <Arguments>--no-watchdog</Arguments>
                </Exec>
              </Actions>
            </Task>
            """.formatted(exePath);
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