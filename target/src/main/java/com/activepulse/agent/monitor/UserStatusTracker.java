package com.activepulse.agent.monitor;

import com.activepulse.agent.util.EnvConfig;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks user activity status based on keyboard/mouse input timestamps.
 * Called by KeyboardMouseTracker whenever an event is seen.
 */
public final class UserStatusTracker {

    private static final UserStatusTracker INSTANCE = new UserStatusTracker();

    private final AtomicLong lastActivityMs = new AtomicLong(System.currentTimeMillis());
    private final long idleThresholdMs;

    private UserStatusTracker() {
        this.idleThresholdMs = EnvConfig.getLong("IDLE_THRESHOLD_SECONDS", 180) * 1000L;
    }

    public static UserStatusTracker getInstance() { return INSTANCE; }

    public void markActivity() {
        lastActivityMs.set(System.currentTimeMillis());
    }

    public UserStatus getStatus() {
        long since = System.currentTimeMillis() - lastActivityMs.get();
        return since >= idleThresholdMs ? UserStatus.IDLE : UserStatus.ACTIVE;
    }

    public long secondsSinceActivity() {
        return (System.currentTimeMillis() - lastActivityMs.get()) / 1000L;
    }
}
