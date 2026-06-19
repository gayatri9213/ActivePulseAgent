package com.activepulse.agent;

import com.activepulse.agent.util.PathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

/**
 * Watchdog mode — keeps the main agent process alive.
 *
 * When ActivePulse.exe is launched with --watchdog, control flows here instead of
 * the normal agent startup. The watchdog spawns the agent as a child process and
 * waits for it to exit. On exit, the watchdog relaunches the agent within ~5 sec.
 *
 * CRITICAL FIX (v1.0.3):
 *   When spawning the child, we now pass "--no-watchdog" as an explicit argument.
 *   This is needed because the jpackage launcher has "--watchdog" baked in via
 *   the --arguments flag in build-installers.yml. Without --no-watchdog, the
 *   spawned child would see --watchdog, try to become a watchdog itself, fail
 *   to acquire watchdog.lock (this process already holds it), and exit with code 2.
 *   Watchdog would then restart it → infinite crash loop.
 *
 * Termination semantics (one-way):
 *   - User kills child agent      → watchdog restarts it in ~5 sec
 *   - User kills watchdog         → both die, stay dead until next login
 *
 * Restart safety:
 *   Capped at 20 restarts per hour to prevent crash loops on bad config.
 */
public final class WatchdogMode {

    private static final Logger log = LoggerFactory.getLogger(WatchdogMode.class);

    private static final long RESTART_DELAY_MS       = 5_000;
    private static final int  MAX_RESTARTS_PER_HOUR  = 20;
    private static final long ONE_HOUR_MS            = 60 * 60 * 1000L;
    private static final long CHILD_KILL_TIMEOUT_SEC = 10;

    private static volatile Process currentChild;
    private static volatile boolean shuttingDown = false;
    private static final Deque<Long> restartTimes = new ConcurrentLinkedDeque<>();

    private WatchdogMode() {}

    public static void run() {
        // Configure log file location BEFORE any logger output
        System.setProperty("activepulse.logs.dir", PathResolver.logsDir().toString());

        log.info("=================================================");
        log.info("  ActivePulse Watchdog starting");
        log.info("  Watchdog PID: {}", ProcessHandle.current().pid());
        log.info("=================================================");

        // Acquire WATCHDOG lock (different file than the agent's lock!)
        SingleInstanceLock lock = new SingleInstanceLock();
        Path watchdogLockFile = PathResolver.dataDir().resolve("watchdog.lock");
        if (!lock.acquire(watchdogLockFile)) {
            log.warn("Another watchdog is already running. Exiting.");
            System.exit(2);
        }

        // Clean up child + lock on Ctrl-C or normal JVM shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shuttingDown = true;
            log.info("Watchdog received shutdown signal -- terminating child...");
            killChildGracefully();
            lock.release();
            log.info("Watchdog stopped.");
        }, "watchdog-shutdown"));

        // Main supervise loop
        while (!shuttingDown) {
            if (exceededRestartLimit()) {
                log.error("Restart limit reached ({} restarts in last hour). " +
                          "Child agent is unhealthy -- giving up. Check earlier logs.",
                        MAX_RESTARTS_PER_HOUR);
                break;
            }
            try {
                Process child = startChild();
                if (child == null) {
                    log.error("Failed to start child. Sleeping 30s before retry.");
                    sleepMs(30_000);
                    continue;
                }
                currentChild = child;
                log.info("Child agent started -- PID {}", child.pid());

                int exitCode = child.waitFor();
                currentChild = null;

                if (shuttingDown) break;

                log.warn("Child exited with code {} -- restarting in {} ms",
                        exitCode, RESTART_DELAY_MS);
                recordRestart();
                sleepMs(RESTART_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                log.error("Unexpected error in supervise loop: {}", t.getMessage(), t);
                sleepMs(RESTART_DELAY_MS);
            }
        }
        log.info("Watchdog exiting.");
    }

    // ─── Child process spawning ──────────────────────────────────────

    /**
     * Spawns the main agent as a child process.
     * CRITICAL: passes "--no-watchdog" so the child skips watchdog mode.
     */
    private static Process startChild() {
        try {
            String launcher = resolveLauncherPath();
            ProcessBuilder pb;

            if (launcher != null) {
                // Production: jpackage-installed launcher with --no-watchdog override
                pb = new ProcessBuilder(launcher, "--no-watchdog");
                log.debug("Spawning child: {} --no-watchdog", launcher);
            } else {
                // Dev fallback: re-exec the same JAR via java
                Path jar = Paths.get(WatchdogMode.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI());
                String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java.exe")
                        .toString();
                pb = new ProcessBuilder(javaBin, "-jar", jar.toString(), "--no-watchdog");
                log.info("Dev mode: spawning child via {} -jar {} --no-watchdog",
                        javaBin, jar);
            }

            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            return pb.start();
        } catch (Exception e) {
            log.error("startChild failed: {}", e.getMessage());
            return null;
        }
    }

    private static String resolveLauncherPath() {
        try {
            Path jar = Paths.get(WatchdogMode.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = jar.getParent();
            // Walk up looking for ActivePulse.exe (jpackage layout)
            for (int i = 0; dir != null && i < 4; i++) {
                Path candidate = dir.resolve("ActivePulse.exe");
                if (Files.isRegularFile(candidate)) {
                    log.info("Resolved launcher: {}", candidate);
                    return candidate.toString();
                }
                dir = dir.getParent();
            }
        } catch (Exception e) {
            log.debug("Launcher resolution failed: {}", e.getMessage());
        }
        return null;  // triggers dev fallback in startChild()
    }

    // ─── Restart rate limiting ───────────────────────────────────────

    private static void recordRestart() {
        long now = System.currentTimeMillis();
        restartTimes.add(now);
        trimOldRestarts(now);
    }

    private static boolean exceededRestartLimit() {
        trimOldRestarts(System.currentTimeMillis());
        return restartTimes.size() >= MAX_RESTARTS_PER_HOUR;
    }

    private static void trimOldRestarts(long now) {
        while (!restartTimes.isEmpty() && (now - restartTimes.peekFirst()) > ONE_HOUR_MS) {
            restartTimes.pollFirst();
        }
    }

    // ─── Shutdown helpers ────────────────────────────────────────────

    private static void killChildGracefully() {
        Process child = currentChild;
        if (child == null || !child.isAlive()) return;
        try {
            child.destroy();
            if (!child.waitFor(CHILD_KILL_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                log.warn("Child didn't exit in {}s -- force kill", CHILD_KILL_TIMEOUT_SEC);
                child.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            child.destroyForcibly();
        }
    }

    private static void sleepMs(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}

// package com.activepulse.agent;

// import com.activepulse.agent.util.PathResolver;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

// import java.nio.file.Files;
// import java.nio.file.Path;
// import java.nio.file.Paths;
// import java.util.Deque;
// import java.util.concurrent.ConcurrentLinkedDeque;
// import java.util.concurrent.TimeUnit;

// /**
//  * Watchdog mode — keeps the main agent process alive.
//  *
//  * When ActivePulse.exe is launched with --watchdog, control flows here instead of
//  * the normal agent startup. The watchdog spawns the agent as a child process and
//  * waits for it to exit. On exit, the watchdog relaunches the agent within ~5 sec.
//  *
//  * Termination semantics (one-way):
//  *   - User kills child agent      → watchdog restarts it in ~5 sec
//  *   - User kills watchdog         → both die, stay dead until next login
//  *
//  * Restart safety:
//  *   Capped at 20 restarts per hour to prevent crash loops on bad config.
//  */
// public final class WatchdogMode {

//     private static final Logger log = LoggerFactory.getLogger(WatchdogMode.class);

//     private static final long RESTART_DELAY_MS       = 5_000;
//     private static final int  MAX_RESTARTS_PER_HOUR  = 20;
//     private static final long ONE_HOUR_MS            = 60 * 60 * 1000L;
//     private static final long CHILD_KILL_TIMEOUT_SEC = 10;

//     private static volatile Process currentChild;
//     private static volatile boolean shuttingDown = false;
//     private static final Deque<Long> restartTimes = new ConcurrentLinkedDeque<>();

//     private WatchdogMode() {}

//     public static void run() {
//         // Configure log file location BEFORE any logger output
//         System.setProperty("activepulse.logs.dir", PathResolver.logsDir().toString());

//         log.info("=================================================");
//         log.info("  ActivePulse Watchdog starting");
//         log.info("  Watchdog PID: {}", ProcessHandle.current().pid());
//         log.info("=================================================");

//         // Acquire WATCHDOG lock (different file than the agent's lock!)
//         SingleInstanceLock lock = new SingleInstanceLock();
//         Path watchdogLockFile = PathResolver.dataDir().resolve("watchdog.lock");
//         if (!lock.acquire(watchdogLockFile)) {
//             log.warn("Another watchdog is already running. Exiting.");
//             System.exit(2);
//         }

//         // Clean up child + lock on Ctrl-C or normal JVM shutdown
//         Runtime.getRuntime().addShutdownHook(new Thread(() -> {
//             shuttingDown = true;
//             log.info("Watchdog received shutdown signal -- terminating child...");
//             killChildGracefully();
//             lock.release();
//             log.info("Watchdog stopped.");
//         }, "watchdog-shutdown"));

//         // Main supervise loop
//         while (!shuttingDown) {
//             if (exceededRestartLimit()) {
//                 log.error("Restart limit reached ({} restarts in last hour). " +
//                           "Child agent is unhealthy -- giving up. Check earlier logs.",
//                         MAX_RESTARTS_PER_HOUR);
//                 break;
//             }
//             try {
//                 Process child = startChild();
//                 if (child == null) {
//                     log.error("Failed to start child. Sleeping 30s before retry.");
//                     sleepMs(30_000);
//                     continue;
//                 }
//                 currentChild = child;
//                 log.info("Child agent started -- PID {}", child.pid());

//                 int exitCode = child.waitFor();
//                 currentChild = null;

//                 if (shuttingDown) break;

//                 log.warn("Child exited with code {} -- restarting in {} ms",
//                         exitCode, RESTART_DELAY_MS);
//                 recordRestart();
//                 sleepMs(RESTART_DELAY_MS);
//             } catch (InterruptedException ie) {
//                 Thread.currentThread().interrupt();
//                 break;
//             } catch (Throwable t) {
//                 log.error("Unexpected error in supervise loop: {}", t.getMessage(), t);
//                 sleepMs(RESTART_DELAY_MS);
//             }
//         }
//         log.info("Watchdog exiting.");
//     }

//     // ─── Child process spawning ──────────────────────────────────────

//     private static Process startChild() {
//         try {
//             String launcher = resolveLauncherPath();
//             ProcessBuilder pb;

//             if (launcher != null) {
//                 // Production: jpackage-installed launcher
//                 pb = new ProcessBuilder(launcher);
//             } else {
//                 // Dev fallback: re-exec the same JAR via java
//                 Path jar = Paths.get(WatchdogMode.class.getProtectionDomain()
//                         .getCodeSource().getLocation().toURI());
//                 String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java.exe")
//                         .toString();
//                 pb = new ProcessBuilder(javaBin, "-jar", jar.toString());
//                 log.info("Dev mode: spawning child via {} -jar {}", javaBin, jar);
//             }

//             pb.redirectErrorStream(true);
//             pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
//             pb.redirectError(ProcessBuilder.Redirect.DISCARD);
//             return pb.start();
//         } catch (Exception e) {
//             log.error("startChild failed: {}", e.getMessage());
//             return null;
//         }
//     }

//     private static String resolveLauncherPath() {
//         try {
//             Path jar = Paths.get(WatchdogMode.class.getProtectionDomain()
//                     .getCodeSource().getLocation().toURI());
//             Path dir = jar.getParent();
//             // Walk up looking for ActivePulse.exe (jpackage layout)
//             for (int i = 0; dir != null && i < 4; i++) {
//                 Path candidate = dir.resolve("ActivePulse.exe");
//                 if (Files.isRegularFile(candidate)) {
//                     log.info("Resolved launcher: {}", candidate);
//                     return candidate.toString();
//                 }
//                 dir = dir.getParent();
//             }
//         } catch (Exception e) {
//             log.debug("Launcher resolution failed: {}", e.getMessage());
//         }
//         return null;  // triggers dev fallback in startChild()
//     }

//     // ─── Restart rate limiting ───────────────────────────────────────

//     private static void recordRestart() {
//         long now = System.currentTimeMillis();
//         restartTimes.add(now);
//         trimOldRestarts(now);
//     }

//     private static boolean exceededRestartLimit() {
//         trimOldRestarts(System.currentTimeMillis());
//         return restartTimes.size() >= MAX_RESTARTS_PER_HOUR;
//     }

//     private static void trimOldRestarts(long now) {
//         while (!restartTimes.isEmpty() && (now - restartTimes.peekFirst()) > ONE_HOUR_MS) {
//             restartTimes.pollFirst();
//         }
//     }

//     // ─── Shutdown helpers ────────────────────────────────────────────

//     private static void killChildGracefully() {
//         Process child = currentChild;
//         if (child == null || !child.isAlive()) return;
//         try {
//             child.destroy();
//             if (!child.waitFor(CHILD_KILL_TIMEOUT_SEC, TimeUnit.SECONDS)) {
//                 log.warn("Child didn't exit in {}s -- force kill", CHILD_KILL_TIMEOUT_SEC);
//                 child.destroyForcibly();
//             }
//         } catch (InterruptedException e) {
//             Thread.currentThread().interrupt();
//             child.destroyForcibly();
//         }
//     }

//     private static void sleepMs(long ms) {
//         try { Thread.sleep(ms); }
//         catch (InterruptedException e) { Thread.currentThread().interrupt(); }
//     }
// }