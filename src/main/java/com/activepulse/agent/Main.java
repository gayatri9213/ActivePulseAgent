package com.activepulse.agent;

import com.activepulse.agent.autostart.AutostartFactory;
import com.activepulse.agent.autostart.AutostartManager;
import com.activepulse.agent.db.DatabaseManager;
import com.activepulse.agent.diagnostics.DiagnosticsFallbackJob;
import com.activepulse.agent.diagnostics.DiagnosticsUploader;
import com.activepulse.agent.job.JobScheduler;
import com.activepulse.agent.monitor.ActivitySessionManager;
import com.activepulse.agent.monitor.AppConfigManager;
import com.activepulse.agent.monitor.KeyboardMouseTracker;
import com.activepulse.agent.sync.SyncManager;
import com.activepulse.agent.util.EnvConfig;
import com.activepulse.agent.util.OsType;
import com.activepulse.agent.util.PathResolver;
import com.activepulse.agent.util.UserFilter;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.TimeZone;
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
 * 10. Schedule diagnostics 12 PM fallback via Quartz
 * 11. Start diagnostics heartbeat + unclean-shutdown catch-up (async)
 * 12. Park main thread until shutdown signal
 *
 * --no-watchdog flag: passed by WatchdogMode when spawning the child.
 * Tells this Main to skip the watchdog branch and run as the agent directly.
 * Without it, the launcher's baked-in --watchdog flag would cause the child
 * to re-enter watchdog mode and crash on lock contention (exit code 2 loop).
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
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

        // ═══ Set log directory BEFORE any logger is initialized ═══
        try {
            Path logsDir = PathResolver.logsDir();
            Files.createDirectories(logsDir);
            System.setProperty("activepulse.logs.dir", logsDir.toString());
        } catch (Throwable ignored) {
            // Never crash on log-dir setup — logback fallback will handle it
        }

        // NOW safe to initialize loggers
        log.info("ActivePulse 1.0.0 starting...");

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

            // Step 8: Scheduler (activity, strokes, screenshots, sync jobs)
            final JobScheduler scheduler = new JobScheduler();
            scheduler.start();

            // ═══════════════════════════════════════════════════════════
            // Step 9: Schedule diagnostics 12 PM fallback via Quartz.
            // Fires uploadDailyFallback() every day at 12:00:00 IST.
            // Skips if shutdown handler already synced today.
            // ═══════════════════════════════════════════════════════════
            try {
                scheduleDiagnosticsFallback(scheduler);
            } catch (Throwable t) {
                log.warn("Failed to schedule diagnostics fallback: {}", t.getMessage());
            }

            // ═══════════════════════════════════════════════════════════
            // Step 10: Diagnostics heartbeat + unclean-shutdown catch-up.
            // Async to keep boot fast. checkPreviousShutdown:
            //   - Reads last-heartbeat.txt
            //   - If stale (> 5 min old) → previous session died uncleanly
            //     → calls uploadOnShutdown() as catch-up
            //   - Then starts the heartbeat writer thread
            // ═══════════════════════════════════════════════════════════
            new Thread(() -> {
                try {
                    DiagnosticsUploader.getInstance().checkPreviousShutdown();
                } catch (Throwable t) {
                    log.debug("Startup shutdown-check failed: {}", t.getMessage());
                }
            }, "activepulse-shutdown-check").start();

            // Step 11: Park — install shutdown hook and wait
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    log.info("Agent shutdown hook fired — running last-mile tasks...");

                    // 1. Flush any in-progress activity session so nothing is lost
                    try {
                        ActivitySessionManager.getInstance().flushCurrent();
                    } catch (Throwable t) {
                        log.warn("flushCurrent failed on shutdown: {}", t.getMessage());
                    }

                    // 2. Run one last sync (activity + screenshots)
                    try {
                        SyncManager.getInstance().syncBeforeShutdown();
                    } catch (Throwable t) {
                        log.warn("syncBeforeShutdown failed: {}", t.getMessage());
                    }

                    // 3. Upload diagnostics logs (yesterday-if-unsynced + today partial)
                    try {
                        DiagnosticsUploader.getInstance().uploadOnShutdown();
                    } catch (Throwable t) {
                        log.warn("Diagnostics uploadOnShutdown failed: {}", t.getMessage());
                    }

                    // 4. Mark clean shutdown (deletes heartbeat file so next
                    //    startup knows this exit was intentional).
                    try {
                        DiagnosticsUploader.getInstance().markCleanShutdown();
                    } catch (Throwable t) {
                        log.warn("markCleanShutdown failed: {}", t.getMessage());
                    }

                    // 5. Stop keyboard/mouse hook cleanly
                    try {
                        KeyboardMouseTracker.getInstance().stop();
                    } catch (Throwable t) {
                        log.warn("KeyboardMouseTracker.stop failed: {}", t.getMessage());
                    }

                    log.info("Shutdown hook complete.");
                } catch (Throwable outer) {
                    // Never let the hook itself crash — that could hang shutdown
                    log.error("Shutdown hook top-level error: {}", outer.getMessage());
                }
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
     * Schedules the diagnostics fallback job with Quartz.
     * Default: every day at 12:00:00 IST (Asia/Kolkata).
     * Cron can be overridden via DIAGNOSTICS_UPLOAD_CRON in agent.env.
     *
     * Misfire policy: fireAndProceed — if the machine was off at 12 PM,
     * the job fires ASAP when the scheduler wakes up.
     *
     * Requires JobScheduler to expose the underlying Quartz Scheduler
     * via getQuartzScheduler(). If your JobScheduler doesn't have this
     * getter, add:
     *
     *     public Scheduler getQuartzScheduler() { return this.scheduler; }
     *
     * where 'scheduler' is the internal Quartz field name.
     */
    private static void scheduleDiagnosticsFallback(JobScheduler jobScheduler) throws Exception {
        Scheduler quartz = jobScheduler.getQuartzScheduler();
        if (quartz == null) {
            log.warn("Quartz scheduler unavailable — diagnostics fallback not scheduled");
            return;
        }

        String cronExpr = EnvConfig.get("DIAGNOSTICS_UPLOAD_CRON", "0 0 12 * * ?");

        JobDetail job = JobBuilder.newJob(DiagnosticsFallbackJob.class)
                .withIdentity("diagnostics.fallback", "diagnostics")
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity("diagnostics.fallback.trigger", "diagnostics")
                .withSchedule(CronScheduleBuilder.cronSchedule(cronExpr)
                        .inTimeZone(TimeZone.getTimeZone("Asia/Kolkata"))
                        .withMisfireHandlingInstructionFireAndProceed())
                .build();

        quartz.scheduleJob(job, trigger);
        log.info("Scheduled diagnostics fallback job with cron: {} (Asia/Kolkata)", cronExpr);
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