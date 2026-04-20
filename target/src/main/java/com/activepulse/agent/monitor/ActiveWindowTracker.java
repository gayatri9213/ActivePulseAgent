package com.activepulse.agent.monitor;

/**
 * Platform-specific active window detection.
 * Returns information about the currently focused application window.
 */
public interface ActiveWindowTracker {
    ActiveWindowInfo getActiveWindow();
}
