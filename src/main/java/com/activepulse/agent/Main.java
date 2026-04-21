package com.activepulse.agent;

import com.activepulse.agent.autostart.AutostartFactory;
import com.activepulse.agent.autostart.AutostartManager;
import com.activepulse.agent.db.DatabaseManager;
import com.activepulse.agent.job.JobScheduler;
import com.activepulse.agent.monitor.ActivitySessionManager;
import com.activepulse.agent.monitor.AppConfigManager;
import com.activepulse.agent.monitor.KeyboardMouseTracker;
import com.activepulse.agent.sync.SyncManager;
import com.activepulse.agent.util.EnvConfig;
import com.activepulse.agent.util.OsType;
import com.activepulse.agent.util.PathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;

/**
 * ActivePulse Agent entry point.
 *
 * Boot sequence:
 *   1. Configure logback log directory (per-user, writable)
 *   2. Load agent.env
 *   3. Acquire single-instance lock
 *   4. Init DatabaseManager (creates schema)
 *   5. Resolve AppConfigManager (triggers AD-user detection on Windows)
 *   6. Register autostart (HKCU on Windows, LaunchAgent on macOS, .desktop on Linux)
 *   7. Start KeyboardMouseTracker (JNativeHook)
 *   8. Start JobScheduler (activity/strokes/screenshots/sync)
 *   9. Park main thread until shutdown signal
 *
 * Shutdown hook flushes the in-progress activity session, stops everything,
 * and triggers a final sync to ensure no data is lost.
 */
public final class Main {

    private static Logger log = null;
    private static final CountDownLatch SHUTDOWN = new CountDownLatch(1);

    public static void main(String[] args) {
        // Step 1: Log directory must be set BEFORE any logger is used.
        // Logback reads ${activepulse.logs.dir} at init time.
        Path logsDir = resolveLogsDirEarly();
        System.setProperty("activepulse.logs.dir", logsDir.toString());
        // Step 1b — Redirect JNativeHook's native lib extraction to a writable
        //           per-user directory. Must be set before GlobalScreen is loaded.
        java.nio.file.Path nativeDir = logsDir.getParent().resolve("native");
        try { java.nio.file.Files.createDirectories(nativeDir); } catch (Exception ignored) {}
        System.setProperty("jnativehook.lib.path", nativeDir.toString());


        // Step 2: Config
        EnvConfig.load();

        log = LoggerFactory.getLogger(Main.class);
        log.info("╔═══════════════════════════════════════════════╗");
        log.info("║  ActivePulse Agent — {}", EnvConfig.get("AGENT_VERSION", "1.0.0"));
        log.info("║  OS:      {}", OsType.displayName());
        log.info("║  Java:    {}", System.getProperty("java.version"));
        log.info("║  LogsDir: {}", logsDir);
        log.info("║  DataDir: {}", PathResolver.dataDir());
        log.info("╚═══════════════════════════════════════════════╝");

        // Step 3: Single instance
        SingleInstanceLock lock = new SingleInstanceLock();
        if (!lock.acquire()) {
            System.exit(2);
        }

        try {
            // Step 4: DB
            DatabaseManager.getInstance();

            // Step 5: Resolve user / device (triggers AD user detection)
            AppConfigManager cfg = AppConfigManager.getInstance();
            DatabaseManager.getInstance().setConfig("username", cfg.getUsername());
            DatabaseManager.getInstance().setConfig("deviceId", cfg.getDeviceId());
            DatabaseManager.getInstance().setConfig("osName",    OsType.displayName());
            DatabaseManager.getInstance().setConfig("userSource", cfg.getUserSource());

            // Step 6: Autostart
            if (EnvConfig.getBool("ENABLE_AUTOSTART", true)) {
                AutostartManager as = AutostartFactory.create();
                if (!as.isInstalled()) {
                    boolean ok = as.install();
                    log.info("Autostart install: {}", ok ? "OK" : "SKIPPED/FAILED");
                }
            }

            // Step 7: Keyboard/mouse hook
            if (EnvConfig.getBool("ENABLE_KEYBOARD_MOUSE", true)) {
                KeyboardMouseTracker.getInstance().start();
            }

            // Step 8: Scheduler
            JobScheduler scheduler = new JobScheduler();
            scheduler.start();

            // Step 9: Park — install shutdown hook and wait
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutdown signal received — cleaning up...");
                try {
                    scheduler.stop();
                } catch (Throwable t) { log.warn("Scheduler stop failed: {}", t.getMessage()); }

                try {
                    KeyboardMouseTracker.getInstance().stop();
                } catch (Throwable t) { log.warn("KM tracker stop failed: {}", t.getMessage()); }

                try {
                    ActivitySessionManager.getInstance().flushCurrent();
                } catch (Throwable t) { log.warn("Activity flush failed: {}", t.getMessage()); }

                try {
                    SyncManager.getInstance().syncBeforeShutdown();
                } catch (Throwable t) { log.warn("Final sync failed: {}", t.getMessage()); }

                try {
                    DatabaseManager.getInstance().shutdown();
                } catch (Throwable t) { log.warn("DB close failed: {}", t.getMessage()); }

                try { lock.release(); } catch (Throwable ignored) {}

                SHUTDOWN.countDown();
                log.info("Agent stopped.");
            }, "activepulse-shutdown"));

            log.info("Agent running. Press Ctrl-C to stop (or SIGTERM).");
            SHUTDOWN.await();

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            log.error("Fatal error in agent main loop: {}", t.getMessage(), t);
            System.exit(1);
        }
    }

    /**
     * Resolve the per-user logs directory without using any logger.
     * Duplicates a bit of PathResolver logic on purpose — PathResolver calls
     * EnvConfig which initializes SLF4J, which locks Logback to the wrong path.
     */
    private static Path resolveLogsDirEarly() {
        String home = System.getProperty("user.home");
        String os   = System.getProperty("os.name", "").toLowerCase();
        Path base;

        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            base = (localAppData != null && !localAppData.isBlank())
                    ? Paths.get(localAppData, "ActivePulse")
                    : Paths.get(home, "AppData", "Local", "ActivePulse");
        } else if (os.contains("mac")) {
            base = Paths.get(home, "Library", "Application Support", "ActivePulse");
        } else {
            String xdg = System.getenv("XDG_DATA_HOME");
            base = (xdg != null && !xdg.isBlank())
                    ? Paths.get(xdg, "activepulse")
                    : Paths.get(home, ".local", "share", "activepulse");
        }

        Path logs = base.resolve("logs");
        try {
            Files.createDirectories(logs);
        } catch (IOException e) {
            // Fallback: temp dir. Don't use System.err — not guaranteed visible on Windows.
            logs = Paths.get(System.getProperty("java.io.tmpdir"), "activepulse-logs");
            try { Files.createDirectories(logs); } catch (Exception ignored) {}
        }
        return logs;
    }

    /**
     * Force Logback to re-read its configuration now that activepulse.logs.dir is set.
     * Uses reflection so we don't pull ch.qos.logback classes at load time, which
     * would themselves trigger Logback init before the system property is set.
     */
    private static void reinitLogback() {
        try {
            // org.slf4j.LoggerFactory.getILoggerFactory() returns a LoggerContext on Logback
            Object factory = LoggerFactory.getILoggerFactory();
            Class<?> loggerContextCls = Class.forName("ch.qos.logback.classic.LoggerContext");
            if (!loggerContextCls.isInstance(factory)) return;

            // factory.reset()
            loggerContextCls.getMethod("reset").invoke(factory);

            // new JoranConfigurator().setContext(factory).doConfigure(logback.xml resource)
            Class<?> joranCls = Class.forName("ch.qos.logback.classic.joran.JoranConfigurator");
            Object configurator = joranCls.getDeclaredConstructor().newInstance();
            joranCls.getMethod("setContext", Class.forName("ch.qos.logback.core.Context"))
                    .invoke(configurator, factory);

            URL url = Main.class.getClassLoader().getResource("logback.xml");
            if (url != null) {
                joranCls.getMethod("doConfigure", URL.class).invoke(configurator, url);
            }
        } catch (Throwable t) {
            // If reinit fails, logs will go to the fallback path — not ideal but not fatal
            System.err.println("Logback reinit failed: " + t.getMessage());
        }
    }


}
