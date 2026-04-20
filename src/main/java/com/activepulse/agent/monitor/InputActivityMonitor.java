package com.activepulse.agent.monitor;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseInputListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
/**
 * InputActivityMonitor — global keyboard + mouse hook via JNativeHook.
 *
 * Registers native OS-level hooks so events are captured regardless of
 * which window is in focus. Counters are atomic — safe to read from the
 * scheduler thread while the hook callbacks fire on JNativeHook's thread.
 *
 * Collected per interval:
 *   keyCount       — total key presses
 *   mouseClicks    — left + right + middle button presses
 *   mouseDistance  — cumulative px distance the mouse has travelled
 */
public class InputActivityMonitor
        implements NativeKeyListener, NativeMouseInputListener {

    private static final Logger log = LoggerFactory.getLogger(InputActivityMonitor.class);

    // ── Atomic counters (written by hook thread, read by scheduler) ──
    private final AtomicInteger keyCount      = new AtomicInteger(0);
    private final AtomicInteger mouseClicks   = new AtomicInteger(0);
    private final AtomicLong    mouseDistBits = new AtomicLong(
            Double.doubleToLongBits(0.0));   // store double as long bits for atomicity

    // Last known mouse position for distance calculation
    private volatile int lastMouseX = -1;
    private volatile int lastMouseY = -1;

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile InputActivityMonitor instance;

    private InputActivityMonitor() {}

    public static InputActivityMonitor getInstance() {
        if (instance == null) {
            synchronized (InputActivityMonitor.class) {
                if (instance == null) instance = new InputActivityMonitor();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────

    public void start() {
        // JNativeHook ships with a very noisy logger — silence it
        java.util.logging.Logger hookLog =
                java.util.logging.Logger.getLogger(GlobalScreen.class.getPackage().getName());
        hookLog.setLevel(Level.OFF);
        hookLog.setUseParentHandlers(false);

        try {
            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(this);
            GlobalScreen.addNativeMouseListener(this);
            GlobalScreen.addNativeMouseMotionListener(this);
            log.info("InputActivityMonitor started — keyboard + mouse hooks active.");
        } catch (NativeHookException e) {
            log.error("Failed to register native hook: {}", e.getMessage());
            log.error("On Linux: ensure DISPLAY is set and xdotool is installed.");
        }
    }

    public void stop() {
        try {
            GlobalScreen.removeNativeKeyListener(this);
            GlobalScreen.removeNativeMouseListener(this);
            GlobalScreen.removeNativeMouseMotionListener(this);
            GlobalScreen.unregisterNativeHook();
            log.info("InputActivityMonitor stopped.");
        } catch (NativeHookException e) {
            log.warn("Error unregistering native hook: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Snapshot & reset  (called by InputActivityRecorder each interval)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Atomically reads all counters and resets them to zero.
     * This gives a clean per-interval reading every time the scheduler fires.
     */
    public InputSnapshot snapshotAndReset() {
        int  keys    = keyCount.getAndSet(0);
        int  clicks  = mouseClicks.getAndSet(0);
        double dist  = Double.longBitsToDouble(
                mouseDistBits.getAndSet(Double.doubleToLongBits(0.0)));
        return new InputSnapshot(keys, clicks, dist);
    }

    /** Peek without resetting — used for logging / debug only. */
    public InputSnapshot peek() {
        return new InputSnapshot(
                keyCount.get(),
                mouseClicks.get(),
                Double.longBitsToDouble(mouseDistBits.get())
        );
    }

    // ─────────────────────────────────────────────────────────────────
    //  NativeKeyListener
    // ─────────────────────────────────────────────────────────────────

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        keyCount.incrementAndGet();
        UserStatusTracker.getInstance().recordEvent(); // real-time status update
    }

    @Override public void nativeKeyReleased(NativeKeyEvent e) {}
    @Override public void nativeKeyTyped(NativeKeyEvent e) {}

    // ─────────────────────────────────────────────────────────────────
    //  NativeMouseInputListener (clicks + movement)
    // ─────────────────────────────────────────────────────────────────

    @Override
    public void nativeMousePressed(NativeMouseEvent e) {
        mouseClicks.incrementAndGet();
        UserStatusTracker.getInstance().recordEvent(); // real-time status update
    }

    @Override
    public void nativeMouseMoved(NativeMouseEvent e) {
        accumulateDistance(e.getX(), e.getY());
    }

    @Override
    public void nativeMouseDragged(NativeMouseEvent e) {
        accumulateDistance(e.getX(), e.getY());
    }

    @Override public void nativeMouseClicked(NativeMouseEvent e) {}
    @Override public void nativeMouseReleased(NativeMouseEvent e) {}

    // ─────────────────────────────────────────────────────────────────
    //  Distance accumulation (Euclidean delta between move events)
    // ─────────────────────────────────────────────────────────────────

    private void accumulateDistance(int x, int y) {
        if (lastMouseX < 0) {
            lastMouseX = x;
            lastMouseY = y;
            return;
        }
        double dx   = x - lastMouseX;
        double dy   = y - lastMouseY;
        double dist = Math.sqrt(dx * dx + dy * dy);
        lastMouseX = x;
        lastMouseY = y;

        // Atomic add on double via CAS loop
        long expected, updated;
        do {
            expected = mouseDistBits.get();
            double newDist = Double.longBitsToDouble(expected) + dist;
            updated = Double.doubleToLongBits(newDist);
        } while (!mouseDistBits.compareAndSet(expected, updated));
    }

    // ─────────────────────────────────────────────────────────────────
    //  Snapshot record
    // ─────────────────────────────────────────────────────────────────

    public record InputSnapshot(int keyCount, int mouseClicks, double mouseDistance) {
        @Override
        public String toString() {
            return String.format("keys=%d clicks=%d distance=%.1fpx",
                    keyCount, mouseClicks, mouseDistance);
        }
    }
}