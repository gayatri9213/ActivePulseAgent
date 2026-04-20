package com.activepulse.agent.monitor;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.platform.win32.WinUser.*;
import com.sun.jna.win32.StdCallLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SystemLockDetector — detects Windows lock (Win+L) and unlock events.
 *
 * Strategy:
 *   Primary   → WTS session notifications via WTSRegisterSessionNotification
 *               Fires WM_WTSSESSION_CHANGE with WTS_SESSION_LOCK /
 *               WTS_SESSION_UNLOCK messages
 *   Fallback  → Poll GetLastInputInfo every 5s — if idle time exceeds
 *               threshold AND GetForegroundWindow returns null → locked
 *
 * On lock:
 *   - UserStatusTracker set to AWAY
 *   - Activity monitoring paused (no false idle counts)
 *
 * On unlock:
 *   - UserStatusTracker reset to IDLE (will move to WORKING on input)
 *   - Monitoring resumes
 */
public class SystemLockDetector {

    private static final Logger log = LoggerFactory.getLogger(SystemLockDetector.class);

    // WTS constants
    private static final int NOTIFY_FOR_THIS_SESSION = 0;
    private static final int WM_WTSSESSION_CHANGE    = 0x02B1;
    private static final int WTS_SESSION_LOCK        = 0x7;
    private static final int WTS_SESSION_UNLOCK      = 0x8;

    // Fallback: if idle > this many seconds AND no foreground window → locked
    private static final int LOCK_IDLE_THRESHOLD_SECS = 10;

    private final AtomicBoolean locked   = new AtomicBoolean(false);
    private final AtomicBoolean running  = new AtomicBoolean(false);

    private ScheduledExecutorService pollScheduler;
    private Thread                   messageThread;

    // ── WTS JNA interface ─────────────────────────────────────────────
    interface Wtsapi32 extends StdCallLibrary {
        Wtsapi32 INSTANCE = Native.load("wtsapi32", Wtsapi32.class);
        boolean WTSRegisterSessionNotification(HWND hwnd, int dwFlags);
        boolean WTSUnRegisterSessionNotification(HWND hwnd);
    }

    // ── GetLastInputInfo for idle time polling ─────────────────────────
    interface ExtUser32 extends StdCallLibrary {
        ExtUser32 INSTANCE = Native.load("user32", ExtUser32.class);

        @Structure.FieldOrder({"cbSize", "dwTime"})
        class LASTINPUTINFO extends com.sun.jna.Structure {
            public int cbSize = 8;
            public int dwTime;
        }

        boolean GetLastInputInfo(LASTINPUTINFO plii);
    }

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile SystemLockDetector instance;
    private SystemLockDetector() {}

    public static SystemLockDetector getInstance() {
        if (instance == null) {
            synchronized (SystemLockDetector.class) {
                if (instance == null) instance = new SystemLockDetector();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────

    public void start() {
        if (!isWindows()) {
            log.info("SystemLockDetector: non-Windows OS — using idle fallback only.");
            startFallbackPoller();
            return;
        }

        running.set(true);

        // Try WTS message loop first
        messageThread = new Thread(this::runMessageLoop, "lock-detector");
        messageThread.setDaemon(true);
        messageThread.start();

        // Always run fallback poller as backup
        startFallbackPoller();

        log.info("SystemLockDetector started (WTS + fallback poller).");
    }

    public void stop() {
        running.set(false);
        if (pollScheduler != null) pollScheduler.shutdownNow();
        if (messageThread  != null) messageThread.interrupt();
        log.info("SystemLockDetector stopped.");
    }

    public boolean isLocked() { return locked.get(); }

    // ─────────────────────────────────────────────────────────────────
    //  WTS message loop (Windows only)
    // ─────────────────────────────────────────────────────────────────

    private void runMessageLoop() {
        try {
            // Create a hidden message-only window to receive WTS events
            HWND msgWnd = User32.INSTANCE.CreateWindowEx(
                    0, "STATIC", "ActivePulseLockWatcher",
                    0, 0, 0, 0, 0,
                    new HWND(Pointer.createConstant(-3)), // HWND_MESSAGE
                    null, null, null
            );

            if (msgWnd == null) {
                log.warn("Could not create message window for WTS — using fallback only.");
                return;
            }

            boolean registered = Wtsapi32.INSTANCE
                    .WTSRegisterSessionNotification(msgWnd, NOTIFY_FOR_THIS_SESSION);

            if (!registered) {
                log.warn("WTSRegisterSessionNotification failed — using fallback only.");
                return;
            }

            log.info("WTS session notifications registered.");

            MSG msg = new MSG();
            while (running.get() && User32.INSTANCE.GetMessage(msg, msgWnd, 0, 0) != 0) {
                if (msg.message == WM_WTSSESSION_CHANGE) {
                    int event = msg.wParam.intValue();
                    if (event == WTS_SESSION_LOCK) {
                        onLocked();
                    } else if (event == WTS_SESSION_UNLOCK) {
                        onUnlocked();
                    }
                }
                User32.INSTANCE.TranslateMessage(msg);
                User32.INSTANCE.DispatchMessage(msg);
            }

            Wtsapi32.INSTANCE.WTSUnRegisterSessionNotification(msgWnd);

        } catch (Exception e) {
            log.warn("WTS message loop error: {} — fallback active.", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Fallback — poll GetLastInputInfo + foreground window check
    // ─────────────────────────────────────────────────────────────────

    private void startFallbackPoller() {
        pollScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lock-poller");
            t.setDaemon(true);
            return t;
        });

        pollScheduler.scheduleAtFixedRate(() -> {
            try {
                // If foreground window is null → screen is locked
                HWND fg = User32.INSTANCE.GetForegroundWindow();
                boolean screenLocked = (fg == null) && getIdleSeconds() > LOCK_IDLE_THRESHOLD_SECS;

                if (screenLocked && !locked.get()) onLocked();
                else if (!screenLocked && locked.get()) onUnlocked();

            } catch (Exception e) {
                log.debug("Lock poll error: {}", e.getMessage());
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private long getIdleSeconds() {
        try {
            ExtUser32.LASTINPUTINFO info = new ExtUser32.LASTINPUTINFO();
            ExtUser32.INSTANCE.GetLastInputInfo(info);
            long ticksNow  = Kernel32.INSTANCE.GetTickCount64();
            long ticksLast = Integer.toUnsignedLong(info.dwTime);
            return (ticksNow - ticksLast) / 1000;
        } catch (Exception e) {
            return 0;
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Lock / Unlock events
    // ─────────────────────────────────────────────────────────────────

    private void onLocked() {
        locked.set(true);
        log.info("System LOCKED — setting status to AWAY.");
        UserStatusTracker.getInstance().setAway();
        AppConfigManager.getInstance().setSleepStatus();
        
        // Save any pending activities with AWAY status
        AppActivityRecorder.getInstance().onSystemLocked();
    }

    private void onUnlocked() {
        locked.set(false);
        log.info("System UNLOCKED — resuming monitoring.");
        UserStatusTracker.getInstance().setUnlocked();
        AppConfigManager.getInstance().setActiveStatus();
        
        // Check if we need to split multi-day away sessions
        AppConfigManager.getInstance().splitMultiDayAwaySession();
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}