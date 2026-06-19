package com.activepulse.agent;
import com.activepulse.agent.WatchdogMode;
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
import com.activepulse.agent.util.UserFilter;
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
 * 0. SKIP-USER GUARD — exit silently if current user is admin/system
 * 1. Watchdog-mode dispatch (if --watchdog flag, hand off to WatchdogMode)
 * 2. Configure logback log directory (per-user, writable)
 * 3. Load agent.env
 * 4. Acquire single-instance lock
 * 5. Init DatabaseManager (creates schema)
 * 6. Resolve AppConfigManager (triggers AD-user detection on Windows)
 * 7. Register autostart (HKLM on Windows, LaunchAgent on macOS, .desktop on Linux)
 * 8. Start KeyboardMouseTracker (JNativeHook)
 * 9. Start JobScheduler (activity/strokes/screenshots/sync)
 * 10. Park main thread until shutdown signal
 *
 * --no-watchdog flag: passed by WatchdogMode when spawning the child.
 * Tells this Main to skip the watchdog branch and run as the agent directly.
 * Without it, the launcher's baked-in --watchdog flag would cause the child
 * to re-enter watchdog mode and crash on lock contention (exit code 2 loop).
 */
public final class Main {

    private static Logger log = null;
    private static final CountDownLatch SHUTDOWN = new CountDownLatch(1);

    public static void main(String[] args) {

        // ═══════════════════════════════════════════════════════════════
        // STEP 0: SKIP-USER GUARD — must run BEFORE any I/O.
        // If the current user is admin/system, exit silently. No folders,
        // no locks, no logback init. Diagnostic marker goes to %TEMP%.
        // ═══════════════════════════════════════════════════════════════
        EnvConfig.load();  // needed early for SKIP_USERS lookup
        if (UserFilter.shouldSkipCurrentUser()) {
            System.exit(0);
            return;
        }

        // ═══════════════════════════════════════════════════════════════
        // STEP 1: MODE DISPATCH
        // --no-watchdog: spawned child from watchdog, run as agent
        // --watchdog:    main launcher invocation, become watchdog
        // (neither):     manual launch, run as agent
        // ═══════════════════════════════════════════════════════════════
        boolean noWatchdog = false;
        boolean watchdogFlag = false;
        if (args != null) {
            for (String arg : args) {
                if ("--no-watchdog".equalsIgnoreCase(arg)) noWatchdog = true;
                if ("--watchdog".equalsIgnoreCase(arg))    watchdogFlag = true;
            }
        }

        // --no-watchdog ALWAYS wins. This is how the watchdog tells its
        // spawned child "you are the agent, don't try to be a watchdog."
        if (watchdogFlag && !noWatchdog) {
            WatchdogMode.run();
            return;
        }

        // ═══════════════════════════════════════════════════════════════
        // STEP 2: Log directory must be set BEFORE any logger is used.
        // Logback reads ${activepulse.logs.dir} at init time.
        // ═══════════════════════════════════════════════════════════════
        Path logsDir = resolveLogsDirEarly();
        System.setProperty("activepulse.logs.dir", logsDir.toString());

        // Step 2b — Redirect JNativeHook's native lib extraction to a writable
        // per-user directory. Must be set before GlobalScreen is loaded.
        Path nativeDir = logsDir.getParent().resolve("native");
        try {
            Files.createDirectories(nativeDir);
        } catch (Exception ignored) {
        }
        System.setProperty("jnativehook.lib.path", nativeDir.toString());

        log = LoggerFactory.getLogger(Main.class);
        log.info("╔═══════════════════════════════════════════════╗");
        log.info("║  ActivePulse Agent — {}", EnvConfig.get("AGENT_VERSION", "1.0.0"));
        log.info("║  OS:      {}", OsType.displayName());
        log.info("║  Java:    {}", System.getProperty("java.version"));
        log.info("║  User:    {}", System.getProperty("user.name"));
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
            DatabaseManager.getInstance().setConfig("osName", OsType.displayName());
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
                } catch (Throwable t) {
                    log.warn("Scheduler stop failed: {}", t.getMessage());
                }

                try {
                    KeyboardMouseTracker.getInstance().stop();
                } catch (Throwable t) {
                    log.warn("KM tracker stop failed: {}", t.getMessage());
                }

                try {
                    ActivitySessionManager.getInstance().flushCurrent();
                } catch (Throwable t) {
                    log.warn("Activity flush failed: {}", t.getMessage());
                }

                try {
                    SyncManager.getInstance().syncBeforeShutdown();
                } catch (Throwable t) {
                    log.warn("Final sync failed: {}", t.getMessage());
                }

                try {
                    DatabaseManager.getInstance().shutdown();
                } catch (Throwable t) {
                    log.warn("DB close failed: {}", t.getMessage());
                }

                try {
                    lock.release();
                } catch (Throwable ignored) {
                }

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
        String os = System.getProperty("os.name", "").toLowerCase();
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
            try {
                Files.createDirectories(logs);
            } catch (Exception ignored) {
            }
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
            Object factory = LoggerFactory.getILoggerFactory();
            Class<?> loggerContextCls = Class.forName("ch.qos.logback.classic.LoggerContext");
            if (!loggerContextCls.isInstance(factory))
                return;

            loggerContextCls.getMethod("reset").invoke(factory);

            Class<?> joranCls = Class.forName("ch.qos.logback.classic.joran.JoranConfigurator");
            Object configurator = joranCls.getDeclaredConstructor().newInstance();
            joranCls.getMethod("setContext", Class.forName("ch.qos.logback.core.Context"))
                    .invoke(configurator, factory);

            URL url = Main.class.getClassLoader().getResource("logback.xml");
            if (url != null) {
                joranCls.getMethod("doConfigure", URL.class).invoke(configurator, url);
            }
        } catch (Throwable t) {
            System.err.println("Logback reinit failed: " + t.getMessage());
        }
    }
}