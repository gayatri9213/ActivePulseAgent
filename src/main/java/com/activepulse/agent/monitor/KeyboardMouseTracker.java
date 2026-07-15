package com.activepulse.agent.monitor;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.github.kwhat.jnativehook.mouse.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Global keyboard/mouse listener.
 *
 * TWO PARALLEL COUNTERS:
 *   1. keyboardCount / mouseCount   — drained every minute by StrokeAggregationJob
 *   2. totalInputs                  — NEVER drained; monotonically increasing
 *      Used by ActivitySessionManager to know if input happened during a
 *      specific session window (snapshot at session start, compare at flush).
 *
 * Also tracks lastInputAtMs (never reset) for absolute idle-time queries.
 */
public final class KeyboardMouseTracker
        implements NativeKeyListener, NativeMouseListener,
        NativeMouseMotionListener, NativeMouseWheelListener {

    private static final Logger log = LoggerFactory.getLogger(KeyboardMouseTracker.class);
    private static final KeyboardMouseTracker INSTANCE = new KeyboardMouseTracker();

    // Drainable counters — reset by StrokeAggregationJob every minute
    private final AtomicInteger keyboardCount = new AtomicInteger(0);
    private final AtomicInteger mouseCount    = new AtomicInteger(0);

    // NEVER drained — used by ActivitySessionManager for per-session delta
    private final AtomicLong totalInputs   = new AtomicLong(0);
    private final AtomicLong lastInputAtMs = new AtomicLong(System.currentTimeMillis());

    private volatile boolean registered = false;
    private long lastMouseMoveMs = 0;
    private static final long MOUSE_MOVE_DEBOUNCE_MS = 1000;

    private KeyboardMouseTracker() {}

    public static KeyboardMouseTracker getInstance() { return INSTANCE; }

    public synchronized void start() {
        if (registered) return;
        try {
            java.nio.file.Path nativeDir = com.activepulse.agent.util.PathResolver
                    .dataDir().resolve("native");
            try { java.nio.file.Files.createDirectories(nativeDir); } catch (Exception ignored) {}
            System.setProperty("jnativehook.lib.path", nativeDir.toString());

            java.util.logging.Logger l = java.util.logging.Logger.getLogger(
                    GlobalScreen.class.getPackage().getName());
            l.setLevel(Level.WARNING);
            l.setUseParentHandlers(false);

            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(this);
            GlobalScreen.addNativeMouseListener(this);
            GlobalScreen.addNativeMouseMotionListener(this);
            GlobalScreen.addNativeMouseWheelListener(this);
            registered = true;

            lastInputAtMs.set(System.currentTimeMillis());
            log.info("Global keyboard/mouse hook registered.");
        } catch (NativeHookException e) {
            log.error("Failed to register native hook: {}", e.getMessage());
        }
    }

    public synchronized void stop() {
        if (!registered) return;
        try {
            GlobalScreen.removeNativeKeyListener(this);
            GlobalScreen.removeNativeMouseListener(this);
            GlobalScreen.removeNativeMouseMotionListener(this);
            GlobalScreen.removeNativeMouseWheelListener(this);
            GlobalScreen.unregisterNativeHook();
            registered = false;
            log.info("Global keyboard/mouse hook unregistered.");
        } catch (NativeHookException e) {
            log.warn("Failed to unregister native hook: {}", e.getMessage());
        }
    }

    /**
     * Reads and zeroes the drainable counters. Called by StrokeAggregationJob.
     * Does NOT reset totalInputs or lastInputAtMs.
     */
    public int[] drain() {
        int k = keyboardCount.getAndSet(0);
        int m = mouseCount.getAndSet(0);
        return new int[] { k, m };
    }

    /** Epoch millis of the last real input event. Never drained. */
    public long getLastInputAtMs() {
        return lastInputAtMs.get();
    }

    /**
     * Monotonically increasing count of ALL input events since agent start.
     * Snapshot this at session start; compare at session end; delta tells you
     * how many events happened during the session (irrespective of drain cycles).
     */
    public long getTotalInputs() {
        return totalInputs.get();
    }

    public long secondsSinceLastInput() {
        long now = System.currentTimeMillis();
        long last = lastInputAtMs.get();
        return Math.max(0, (now - last) / 1000);
    }

    // ─── Listener callbacks — every event updates BOTH totalInputs AND lastInputAtMs ─

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        keyboardCount.incrementAndGet();
        totalInputs.incrementAndGet();
        lastInputAtMs.set(System.currentTimeMillis());
        UserStatusTracker.getInstance().markActivity();
    }

    @Override
    public void nativeMousePressed(NativeMouseEvent e) {
        mouseCount.incrementAndGet();
        totalInputs.incrementAndGet();
        lastInputAtMs.set(System.currentTimeMillis());
        UserStatusTracker.getInstance().markActivity();
    }

    @Override
    public void nativeMouseMoved(NativeMouseEvent e) {
        long now = System.currentTimeMillis();
        if (now - lastMouseMoveMs > MOUSE_MOVE_DEBOUNCE_MS) {
            mouseCount.incrementAndGet();
            totalInputs.incrementAndGet();   // debounced — max 1 per sec for mouse move
            lastMouseMoveMs = now;
        }
        lastInputAtMs.set(now);
        UserStatusTracker.getInstance().markActivity();
    }

    @Override public void nativeMouseDragged(NativeMouseEvent e) { nativeMouseMoved(e); }

    @Override
    public void nativeMouseWheelMoved(NativeMouseWheelEvent e) {
        mouseCount.incrementAndGet();
        totalInputs.incrementAndGet();
        lastInputAtMs.set(System.currentTimeMillis());
        UserStatusTracker.getInstance().markActivity();
    }

    // Unused defaults
    @Override public void nativeKeyReleased(NativeKeyEvent e) {}
    @Override public void nativeKeyTyped(NativeKeyEvent e) {}
    @Override public void nativeMouseReleased(NativeMouseEvent e) {}
    @Override public void nativeMouseClicked(NativeMouseEvent e) {}
}