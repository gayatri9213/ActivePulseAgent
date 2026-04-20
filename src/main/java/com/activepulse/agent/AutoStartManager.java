package com.activepulse.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * AutoStartManager — registers the agent to start on OS login.
 *
 * Windows → HKCU registry Run key   (no admin required, uses javaw.exe)
 * macOS   → LaunchAgent plist
 * Linux   → systemd user service
 *
 * Key design: install() is called on EVERY startup to ensure the
 * registry entry is always current (correct JAR path, correct Java path).
 */
public class AutoStartManager {

    private static final Logger log = LoggerFactory.getLogger(AutoStartManager.class);

    private static final String OS       = System.getProperty("os.name", "").toLowerCase();
    private static final String JAR_PATH = resolveJarPath();

    // ── Use javaw.exe on Windows (no console window on auto-start) ───
    private static final String JAVA_BIN = resolveJavaBin();

    private static final String TASK_NAME    = "ActivePulseAgent";
    private static final String PLIST_ID     = "com.activepulse.agent";
    private static final String SERVICE_NAME = "activepulse";

    private static final String WIN_REG_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String WIN_REG_KEY_MACHINE =
            "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile AutoStartManager instance;
    private AutoStartManager() {}

    public static AutoStartManager getInstance() {
        if (instance == null) {
            synchronized (AutoStartManager.class) {
                if (instance == null) instance = new AutoStartManager();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────

    /**
     * Always re-installs on every startup — ensures JAR path and
     * Java path are always up to date in the registry.
     */
    public void install() {
        log.info("Registering auto-start...");
        log.info("  JAR  : {}", JAR_PATH);
        log.info("  Java : {}", JAVA_BIN);

        if (isWindows()) installWindows();
        else if (isMac()) installMac();
        else              installLinux();
    }

    public void uninstall() {
        log.info("Removing auto-start...");
        if (isWindows()) uninstallWindows();
        else if (isMac()) uninstallMac();
        else              uninstallLinux();
    }

    public boolean isInstalled() {
        if (isWindows()) return isInstalledWindows();
        if (isMac())     return isInstalledMac();
        return isInstalledLinux();
    }

    // ─────────────────────────────────────────────────────────────────
    //  Windows — Registry Run keys (HKCU for user, HKLM for machine-wide)
    //  Uses javaw.exe so no console window appears on auto-start
    // ─────────────────────────────────────────────────────────────────

    /**
     * Check if running with administrator privileges on Windows
     */
    private boolean hasAdminRights() {
        try {
            // Try to read HKLM registry key - requires admin rights
            return exec("reg", "query", "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion") == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Determine if should use machine-wide or user-specific installation
     */
    private boolean shouldUseMachineWide() {
        // Use machine-wide if:
        // 1. Running with admin rights
        // 2. Installed in Program Files (machine-wide location)
        String exePath = resolveNativeExePath();
        if (exePath != null) {
            return exePath.contains("Program Files") && hasAdminRights();
        }
        return hasAdminRights();
    }

    /**
     * Resolves the correct executable to register for auto-start.
     *
     * Running as jpackage .exe  → register ActivePulse.exe directly
     * Running as plain JAR      → register javaw.exe -jar agent.jar
     */
    private void installWindows() {
        String value;
        String exePath = resolveNativeExePath();

        if (exePath != null) {
            // Running inside jpackage app-image — use the .exe directly
            value = "\"" + exePath + "\"";
            log.info("Auto-start: using native exe: {}", exePath);
        } else {
            // Plain JAR run
            value = String.format("\"%s\" -jar \"%s\"", JAVA_BIN, JAR_PATH);
            log.info("Auto-start: using JAR launch: {}", value);
        }

        // Determine which registry key to use
        boolean useMachineWide = shouldUseMachineWide();
        String regKey = useMachineWide ? WIN_REG_KEY_MACHINE : WIN_REG_KEY;
        String scope = useMachineWide ? "machine-wide" : "user-specific";
        
        log.info("Installing auto-start ({}): {}", scope, useMachineWide ? "HKLM" : "HKCU");

        // Escape quotes for registry command
        String regValue = value.replace("\"", "\\\"");
        int exit = exec("reg", "add", regKey,
                "/v", TASK_NAME,
                "/t", "REG_SZ",
                "/d", regValue,
                "/f");

        if (exit == 0) {
            log.info("Auto-start registered in Windows Registry ({}).", scope);
            log.info("  Key   : {}\\{}", regKey, TASK_NAME);
            log.info("  Value : {}", value);
        } else {
            log.error("Registry write failed (exit {}) for {}", exit, scope);
            // Fallback to user-specific if machine-wide failed
            if (useMachineWide) {
                log.info("Attempting fallback to user-specific registry...");
                String fallbackRegValue = value.replace("\"", "\\\"");
                int fallbackExit = exec("reg", "add", WIN_REG_KEY,
                        "/v", TASK_NAME,
                        "/t", "REG_SZ",
                        "/d", fallbackRegValue,
                        "/f");
                if (fallbackExit == 0) {
                    log.info("Auto-start registered in user-specific Registry (fallback).");
                    log.info("  Key   : {}\\{}", WIN_REG_KEY, TASK_NAME);
                } else {
                    log.error("Both machine-wide and user-specific registry writes failed.");
                }
            }
        }
    }

    private void uninstallWindows() {
    boolean useMachineWide = shouldUseMachineWide();
    String regKey = useMachineWide ? WIN_REG_KEY_MACHINE : WIN_REG_KEY;
    String scope = useMachineWide ? "machine-wide" : "user-specific";
    
    log.info("Removing auto-start ({}): {}", scope, useMachineWide ? "HKLM" : "HKCU");
    
    int exit = exec("reg", "delete", regKey, "/v", TASK_NAME, "/f");
    if (exit == 0) {
        log.info("Auto-start registry entry removed ({}).", scope);
    } else if (exit == 2) {
        log.info("Auto-start registry entry not found ({}).", scope);
    } else {
        log.error("Registry delete failed (exit {}) for {}", exit, scope);
    }
    
    // Also try to remove from the other registry key (cleanup)
    String otherRegKey = useMachineWide ? WIN_REG_KEY : WIN_REG_KEY_MACHINE;
    String otherScope = useMachineWide ? "user-specific" : "machine-wide";
    int otherExit = exec("reg", "delete", otherRegKey, "/v", TASK_NAME, "/f");
    if (otherExit == 0) {
        log.info("Additional auto-start entry removed ({}).", otherScope);
    } else if (otherExit != 2) {
        log.debug("No additional entry found in {} registry.", otherScope);
    }
}
 
private boolean isInstalledWindows() {
    // Check both machine-wide and user-specific registry keys
    boolean machineWide = exec("reg", "query", WIN_REG_KEY_MACHINE, "/v", TASK_NAME) == 0;
    boolean userSpecific = exec("reg", "query", WIN_REG_KEY, "/v", TASK_NAME) == 0;
    
    if (machineWide) log.debug("Found machine-wide auto-start entry.");
    if (userSpecific) log.debug("Found user-specific auto-start entry.");
    
    return machineWide || userSpecific;
}

    /**
     * If running inside a jpackage app-image, the .exe lives one level
     * above the app/runtime folder.
     * Structure:  ActivePulse\
     *               ActivePulse.exe      ← this is what we want
     *               app\
     *               runtime\
     */
    private String resolveNativeExePath() {
        try {
            Path self = Paths.get(
                    AutoStartManager.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI()
            ).toAbsolutePath();

            // Walk up to find .exe next to the app/ folder
            Path appDir    = self.getParent();   // …/ActivePulse/app/
            Path installDir = appDir.getParent(); // …/ActivePulse/

            // Try multiple possible exe names - jpackage creates ActivePulse.exe
            String[] possibleNames = {"ActivePulse.exe", "ActivePulseAgent.exe"};

            for (String exeName : possibleNames) {
                Path exe = installDir.resolve(exeName);
                if (Files.exists(exe)) {
                    log.debug("Found native exe: {}", exe);
                    return exe.toString();
                }
            }

            // Also check the current directory
            Path currentDir = Paths.get(".").toAbsolutePath();
            for (String exeName : possibleNames) {
                Path exe = currentDir.resolve(exeName);
                if (Files.exists(exe)) {
                    log.debug("Found exe in current dir: {}", exe);
                    return exe.toString();
                }
            }

            // Check common jpackage installation paths
            String[] installPaths = {
                "C:\\Program Files\\ActivePulse",
                "C:\\Program Files (x86)\\ActivePulse",
                System.getProperty("user.home") + "\\AppData\\Local\\ActivePulse"
            };

            for (String installPath : installPaths) {
                Path installDirPath = Paths.get(installPath);
                if (Files.exists(installDirPath)) {
                    for (String exeName : possibleNames) {
                        Path exe = installDirPath.resolve(exeName);
                        if (Files.exists(exe)) {
                            log.debug("Found exe in install path: {}", exe);
                            return exe.toString();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error resolving native exe path: {}", e.getMessage());
        }
        return null;
    }


    private void installMac() {
        Path plistDir  = Paths.get(System.getProperty("user.home"), "Library", "LaunchAgents");
        Path plistFile = plistDir.resolve(PLIST_ID + ".plist");

        String plist = String.format("""
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
              "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>Label</key>
                <string>%s</string>
                <key>ProgramArguments</key>
                <array>
                    <string>%s</string>
                    <string>-jar</string>
                    <string>%s</string>
                </array>
                <key>RunAtLoad</key>
                <true/>
                <key>KeepAlive</key>
                <true/>
                <key>StandardOutPath</key>
                <string>%s</string>
                <key>StandardErrorPath</key>
                <string>%s</string>
                <key>ProcessType</key>
                <string>Background</string>
            </dict>
            </plist>
            """,
                PLIST_ID, JAVA_BIN, JAR_PATH,
                logPath("activepulse-stdout.log"),
                logPath("activepulse-stderr.log")
        );

        try {
            Files.createDirectories(plistDir);
            Files.writeString(plistFile, plist);
            exec("launchctl", "unload", "-w", plistFile.toString()); // unload first to refresh
            exec("launchctl", "load",   "-w", plistFile.toString());
            log.info("LaunchAgent installed: {}", plistFile);
        } catch (IOException e) {
            log.error("Failed to write LaunchAgent plist: {}", e.getMessage());
        }
    }

    private void uninstallMac() {
        Path plistFile = Paths.get(System.getProperty("user.home"),
                "Library", "LaunchAgents", PLIST_ID + ".plist");
        exec("launchctl", "unload", "-w", plistFile.toString());
        try { Files.deleteIfExists(plistFile); }
        catch (IOException e) { log.warn("Could not delete plist: {}", e.getMessage()); }
        log.info("LaunchAgent removed.");
    }

    private boolean isInstalledMac() {
        return Files.exists(Paths.get(System.getProperty("user.home"),
                "Library", "LaunchAgents", PLIST_ID + ".plist"));
    }

    // ─────────────────────────────────────────────────────────────────
    //  Linux — systemd user service
    // ─────────────────────────────────────────────────────────────────

    private void installLinux() {
        Path serviceDir  = Paths.get(System.getProperty("user.home"),
                ".config", "systemd", "user");
        Path serviceFile = serviceDir.resolve(SERVICE_NAME + ".service");

        String unit = String.format("""
            [Unit]
            Description=ActivePulse Desktop Activity Agent
            After=graphical-session.target

            [Service]
            Type=simple
            ExecStart=%s -jar %s
            Restart=on-failure
            RestartSec=10
            StandardOutput=append:%s
            StandardError=append:%s
            Environment=DISPLAY=:0
            Environment=XAUTHORITY=%s/.Xauthority

            [Install]
            WantedBy=default.target
            """,
                JAVA_BIN, JAR_PATH,
                logPath("activepulse-stdout.log"),
                logPath("activepulse-stderr.log"),
                System.getProperty("user.home")
        );

        try {
            Files.createDirectories(serviceDir);
            Files.writeString(serviceFile, unit);
            try {
                Files.setPosixFilePermissions(serviceFile,
                        Set.of(PosixFilePermission.OWNER_READ,
                                PosixFilePermission.OWNER_WRITE,
                                PosixFilePermission.GROUP_READ,
                                PosixFilePermission.OTHERS_READ));
            } catch (UnsupportedOperationException ignored) {}

            exec("systemctl", "--user", "daemon-reload");
            exec("systemctl", "--user", "enable", SERVICE_NAME);
            log.info("systemd service installed: {}", serviceFile);
        } catch (IOException e) {
            log.error("Failed to write systemd unit: {}", e.getMessage());
        }
    }

    private void uninstallLinux() {
        exec("systemctl", "--user", "stop",    SERVICE_NAME);
        exec("systemctl", "--user", "disable", SERVICE_NAME);
        Path serviceFile = Paths.get(System.getProperty("user.home"),
                ".config", "systemd", "user", SERVICE_NAME + ".service");
        try {
            Files.deleteIfExists(serviceFile);
            exec("systemctl", "--user", "daemon-reload");
        } catch (IOException e) {
            log.warn("Could not delete service file: {}", e.getMessage());
        }
        log.info("systemd service removed.");
    }

    private boolean isInstalledLinux() {
        return Files.exists(Paths.get(System.getProperty("user.home"),
                ".config", "systemd", "user", SERVICE_NAME + ".service"));
    }

    // ─────────────────────────────────────────────────────────────────
    //  Resolve paths
    // ─────────────────────────────────────────────────────────────────

    private static String resolveJarPath() {
        try {
            return Paths.get(
                    AutoStartManager.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI()
            ).toAbsolutePath().toString();
        } catch (Exception e) {
            return System.getProperty("user.home") + "/.activepulse/active-pulse-0.0.1-SNAPSHOT.jar";
        }
    }

    /**
     * On Windows: prefer javaw.exe (no console window).
     * On other OS: use java.
     */
    private static String resolveJavaBin() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            // Try javaw.exe first (Windows, no console window)
            Path javaw = Paths.get(javaHome, "bin", "javaw.exe");
            if (Files.exists(javaw)) return javaw.toAbsolutePath().toString();

            // Fallback to java / java.exe
            String exe = isWindows() ? "java.exe" : "java";
            Path java  = Paths.get(javaHome, "bin", exe);
            if (Files.exists(java)) return java.toAbsolutePath().toString();
        }
        return "java";
    }

    // ─────────────────────────────────────────────────────────────────
    //  Utilities
    // ─────────────────────────────────────────────────────────────────

    private int exec(String... cmd) {
        try {
            log.debug("Executing: {}", String.join(" ", cmd));
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            
            // Capture output for debugging registry issues
            StringBuilder output = new StringBuilder();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            int exitCode = p.waitFor();
            if (exitCode != 0 && cmd.length > 0 && cmd[0].equals("reg")) {
                log.error("Registry command failed with exit {}: {}", exitCode, output.toString());
            }
            return exitCode;
        } catch (Exception e) {
            log.error("exec({}) error: {}", String.join(" ", cmd), e.getMessage());
            return -1;
        }
    }

    private String logPath(String fileName) {
        return Paths.get(System.getProperty("user.home"),
                ".activepulse", "logs", fileName).toString();
    }

    private static boolean isWindows() { return OS.contains("win"); }
    private static boolean isMac()     { return OS.contains("mac"); }
}