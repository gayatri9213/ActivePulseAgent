package com.activepulse.agent;

import com.activepulse.agent.db.DatabaseManager;
import com.activepulse.agent.monitor.AppActivityRecorder;
import com.activepulse.agent.monitor.AppConfigManager;
import com.activepulse.agent.monitor.InputActivityMonitor;
import com.activepulse.agent.monitor.InputActivityRecorder;
import com.activepulse.agent.monitor.ScreenshotRecorder;
import com.activepulse.agent.monitor.SystemLockDetector;
import com.activepulse.agent.monitor.SystemMetricsRecorder;
import com.activepulse.agent.monitor.UserStatusTracker;
import com.activepulse.agent.scheduler.AgentScheduler;
import com.activepulse.agent.sync.SyncConfig;
import com.activepulse.agent.sync.SyncManager;
import com.activepulse.agent.util.EnvConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;

public class ActivePulseApplication {

    private static final Logger log = LoggerFactory.getLogger(ActivePulseApplication.class);
    private volatile boolean running = true;

    // ─────────────────────────────────────────────────────────────────
    //  Entry point
    //
    //  Supported CLI flags:
    //    --install    install auto-start and exit
    //    --uninstall  remove  auto-start and exit
    //    (no args)    run normally
    // ─────────────────────────────────────────────────────────────────

    public static void main(String[] args) {

        // ── STEP 1: Instance lock — MUST be the very first check ──────
        // Before ANY logging or DB init.
        // Prevents duplicate instances and double logs on machine restart.
        if (!InstanceLock.acquire()) {
            System.err.println("[ActivePulse] Already running — exiting.");
            System.exit(0);
        }

        // ── STEP 2: Ensure log/data directories exist ─────────────────
        ensureDirectories();

        // ── STEP 4: Handle CLI flags ───────────────────────────────────
        if (args.length > 0) {
            switch (args[0].toLowerCase()) {
                case "--install" -> {
                    DatabaseManager.getInstance().init();
                    AutoStartManager.getInstance().install();
                    log.info("Auto-start installed.");
                    System.exit(0);
                }
                case "--uninstall" -> {
                    AutoStartManager.getInstance().uninstall();
                    log.info("Auto-start removed.");
                    System.exit(0);
                }
                default -> log.warn("Unknown argument '{}' — starting normally.", args[0]);
            }
        }

        new ActivePulseApplication().start();
    }

    // ─────────────────────────────────────────────────────────────────
    //  Agent startup — ordered carefully
    // ─────────────────────────────────────────────────────────────────

    private void start() {
        log.info("╔══════════════════════════════════════╗");
        log.info("║   ActivePulse Desktop Agent v1.0.0   ║");
        log.info("╚══════════════════════════════════════╝");
        log.info("Agent starting at {}",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        // ── 1. Database — must be before any recorder or sync
        DatabaseManager.getInstance().init();
        writeAgentConfig();
        SyncConfig.getInstance().seed();

        // ── 3. Init sync directories + session
        SyncManager.getInstance();
        AppConfigManager.getInstance().start();

        // ── 4. Auto-start — re-register on every startup (keeps path current)
        installAutoStart();

        // ── 5. System lock detector — before monitors so lock state is known
        SystemLockDetector.getInstance().start();

        // ── 6. Monitors
        AppActivityRecorder.getInstance().start();
        InputActivityMonitor.getInstance().start();
        InputActivityRecorder.getInstance().start();
        ScreenshotRecorder.getInstance().start();
        SystemMetricsRecorder.getInstance().start();

        // ── 7. Quartz scheduler — drives all periodic jobs
        AgentScheduler.getInstance().start();

        registerShutdownHook();

        log.info("Agent is running. Press Ctrl+C to stop.");
        keepAlive();
    }

    // ─────────────────────────────────────────────────────────────────
    //  Auto-start
    // ─────────────────────────────────────────────────────────────────

    private void installAutoStart() {
        // Always re-register to keep JAR path and Java path current
        AutoStartManager.getInstance().install();
    }

    // ─────────────────────────────────────────────────────────────────
    //  Write machine identity to agent_config
    // ─────────────────────────────────────────────────────────────────

    private void writeAgentConfig() {
        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO agent_config (key, value) VALUES (?, ?)")) {

                String[][] config = {
                        {"deviceId",     "DEV-" + getMacAddress()},
                        {"osName",       System.getProperty("os.name")},
                        {"osVersion",    System.getProperty("os.version")},
                        {"osArch",       System.getProperty("os.arch")},
                        {"javaVersion",  System.getProperty("java.version")},
                        {"userName",     System.getProperty("user.name")},
                        {"agentVersion", EnvConfig.get("AGENT_VERSION", "1.0.0")},
                        {"startedAt",    Instant.now().toString()},
                        {"sessionStart", Instant.now().toString()},
                };
                for (String[] kv : config) {
                    ps.setString(1, kv[0]);
                    ps.setString(2, kv[1]);
                    ps.executeUpdate();
                }
                log.info("Agent config written — device: DEV-{} user: {}",
                        getMacAddress(), System.getProperty("user.name"));
            }
        } catch (SQLException e) {
            log.error("Failed to write agent config", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Graceful shutdown — reverse of startup order
    // ─────────────────────────────────────────────────────────────────

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received — stopping agent...");
            running = false;

            // Stop in reverse startup order
            AgentScheduler.getInstance().stop();
            AppActivityRecorder.getInstance().stop();
            InputActivityRecorder.getInstance().stop();
            InputActivityMonitor.getInstance().stop();
            ScreenshotRecorder.getInstance().stop();
            SystemMetricsRecorder.getInstance().stop();
            SystemLockDetector.getInstance().stop();
            AppConfigManager.getInstance().stop();
            UserStatusTracker.getInstance().setStopped();
            InstanceLock.release();
            DatabaseManager.getInstance().close();

            log.info("ActivePulse Agent stopped cleanly.");
        }, "shutdown-hook"));
    }

    // ─────────────────────────────────────────────────────────────────
    //  Utilities
    // ─────────────────────────────────────────────────────────────────

    private void keepAlive() {
        while (running) {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }

    /**
     * Stable device ID from MAC address of first non-loopback NIC.
     * Format: A4C3F0112233
     * Fallback: hash of username + hostname
     */
    private String getMacAddress() {
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (nic.isLoopback() || !nic.isUp()) continue;
                byte[] mac = nic.getHardwareAddress();
                if (mac == null || mac.length == 0) continue;
                StringBuilder sb = new StringBuilder();
                for (byte b : mac) sb.append(String.format("%02X", b));
                return sb.toString();
            }
        } catch (Exception ignored) {}
        // Fallback
        try {
            String raw = System.getProperty("user.name")
                    + java.net.InetAddress.getLocalHost().getHostName();
            return String.valueOf(Math.abs(raw.hashCode()));
        } catch (Exception e) {
            return "000000";
        }
    }

    private static void ensureDirectories() {
        Path logDir = Paths.get(System.getProperty("user.home"), ".activepulse", "logs");
        if (!Files.exists(logDir)) {
            try {
                Files.createDirectories(logDir);
                System.out.println("[ActivePulse] Created log directory: " + logDir);
            } catch (IOException e) {
                System.err.println("[ActivePulse] WARNING: " + e.getMessage());
            }
        }
    }
}