package com.activepulse.agent.monitor;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.github.kwhat.jnativehook.mouse.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Global keyboard/mouse listener that maintains rolling counts until
 * StrokeAggregationJob reads & resets them (once per minute).
 */
public final class KeyboardMouseTracker
        implements NativeKeyListener, NativeMouseListener, NativeMouseMotionListener, NativeMouseWheelListener {

    private static final Logger log = LoggerFactory.getLogger(KeyboardMouseTracker.class);
    private static final KeyboardMouseTracker INSTANCE = new KeyboardMouseTracker();

    private final AtomicInteger keyboardCount = new AtomicInteger(0);
    private final AtomicInteger mouseCount    = new AtomicInteger(0);

    private volatile boolean registered = false;
    private long lastMouseMoveMs = 0;
    private static final long MOUSE_MOVE_DEBOUNCE_MS = 1000; // count at most 1 move/sec

    private KeyboardMouseTracker() {}

    public static KeyboardMouseTracker getInstance() { return INSTANCE; }

    public synchronized void start() {
        if (registered) return;
        try {
            // Silence JNativeHook's java.util.logging output
            java.util.logging.Logger l = java.util.logging.Logger.getLogger(GlobalScreen.class.getPackage().getName());
            l.setLevel(Level.WARNING);
            l.setUseParentHandlers(false);

            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(this);
            GlobalScreen.addNativeMouseListener(this);
            GlobalScreen.addNativeMouseMotionListener(this);
            GlobalScreen.addNativeMouseWheelListener(this);
            registered = true;
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
     * Reads and zeroes both counters. Called by StrokeAggregationJob.
     */
    public int[] drain() {
        int k = keyboardCount.getAndSet(0);
        int m = mouseCount.getAndSet(0);
        return new int[] { k, m };
    }

    // ─── Listener callbacks ──────────────────────────────────────────

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        keyboardCount.incrementAndGet();
        UserStatusTracker.getInstance().markActivity();
    }

    @Override
    public void nativeMousePressed(NativeMouseEvent e) {
        mouseCount.incrementAndGet();
        UserStatusTracker.getInstance().markActivity();
    }

    @Override
    public void nativeMouseMoved(NativeMouseEvent e) {
        long now = System.currentTimeMillis();
        if (now - lastMouseMoveMs > MOUSE_MOVE_DEBOUNCE_MS) {
            mouseCount.incrementAndGet();
            lastMouseMoveMs = now;
        }
        UserStatusTracker.getInstance().markActivity();
    }

    @Override public void nativeMouseDragged(NativeMouseEvent e) { nativeMouseMoved(e); }
    @Override public void nativeMouseWheelMoved(NativeMouseWheelEvent e) {
        mouseCount.incrementAndGet();
        UserStatusTracker.getInstance().markActivity();
    }

    // Unused defaults
    @Override public void nativeKeyReleased(NativeKeyEvent e) {}
    @Override public void nativeKeyTyped(NativeKeyEvent e) {}
    @Override public void nativeMouseReleased(NativeMouseEvent e) {}
    @Override public void nativeMouseClicked(NativeMouseEvent e) {}
}
