package com.activepulse.agent.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * UserStatusTracker — real-time activity classification.
 *
 * Normal idle timeout  : 2 minutes  (no input → IDLE)
 * Video idle timeout   : 10 minutes (watching YouTube etc. → stay WORKING)
 * Away timeout         : 5 minutes after IDLE or system lock
 *
 * Thresholds (events in 60s sliding window):
 *   WORKING  ≥ 30 events
 *   NEUTRAL   5–29 events
 *   IDLE     < 5 events  for ≥ IDLE_TIMEOUT
 *   AWAY       no input  for ≥ AWAY_TIMEOUT
 *   STOPPED    agent shutdown
 */
public class UserStatusTracker {

    private static final Logger log = LoggerFactory.getLogger(UserStatusTracker.class);

    // ── Thresholds ────────────────────────────────────────────────────
    private static final int  WORKING_THRESHOLD        = 30;   // events/60s
    private static final int  NEUTRAL_THRESHOLD        = 5;    // events/60s
    private static final long IDLE_TIMEOUT_SECS        = 120;  // 2 min
    private static final long VIDEO_IDLE_TIMEOUT_SECS  = 600;  // 10 min
    private static final long AWAY_TIMEOUT_SECS        = 300;  // 5 min
    private static final long WINDOW_SECS              = 60;   // sliding window

    // ── State ─────────────────────────────────────────────────────────
    private volatile UserStatus currentStatus    = UserStatus.IDLE;
    private volatile Instant    lastActiveTime   = Instant.now();
    private volatile boolean    systemLocked     = false;
    private volatile boolean    watchingVideo    = false;  // ← video mode flag

    private final Deque<Instant> eventWindow = new ArrayDeque<>();
    private volatile Runnable    onStatusChanged;

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile UserStatusTracker instance;
    private UserStatusTracker() {}

    public static UserStatusTracker getInstance() {
        if (instance == null) {
            synchronized (UserStatusTracker.class) {
                if (instance == null) instance = new UserStatusTracker();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Record raw input event (called from JNativeHook callbacks)
    // ─────────────────────────────────────────────────────────────────

    public synchronized void recordEvent() {
        if (systemLocked) return;
        Instant now = Instant.now();
        eventWindow.addLast(now);
        lastActiveTime = now;
        pruneWindow(now);
    }

    // ─────────────────────────────────────────────────────────────────
    //  Evaluate — called by InputActivityRecorder every 60s flush
    // ─────────────────────────────────────────────────────────────────

    public synchronized void evaluate() {
        if (systemLocked) return;

        Instant now           = Instant.now();
        pruneWindow(now);

        int  eventsInWindow   = eventWindow.size();
        long idleSecs         = ChronoUnit.SECONDS.between(lastActiveTime, now);

        // Pick correct idle timeout based on whether user is watching video
        long idleTimeout = watchingVideo
                ? VIDEO_IDLE_TIMEOUT_SECS   // 10 min for video
                : IDLE_TIMEOUT_SECS;        // 2 min normal

        UserStatus newStatus;

        if (idleSecs >= AWAY_TIMEOUT_SECS && !watchingVideo) {
            // Away — only if NOT watching video
            newStatus = UserStatus.AWAY;

        } else if (idleSecs >= idleTimeout) {
            // Idle timeout reached
            newStatus = UserStatus.IDLE;

        } else if (eventsInWindow >= WORKING_THRESHOLD) {
            newStatus = UserStatus.WORKING;

        } else if (eventsInWindow >= NEUTRAL_THRESHOLD) {
            newStatus = UserStatus.NEUTRAL;

        } else if (watchingVideo && idleSecs < VIDEO_IDLE_TIMEOUT_SECS) {
            // No input but watching video — keep WORKING for up to 10 min
            newStatus = UserStatus.WORKING;

        } else {
            newStatus = UserStatus.IDLE;
        }

        applyStatus(newStatus);
    }

    /**
     * Legacy evaluate() — called from InputActivityRecorder flush.
     * Feeds events into window, then evaluates.
     */
    public void evaluate(int keyCount, int mouseClicks) {
        int total = keyCount + mouseClicks;
        if (total > 0) {
            Instant now = Instant.now();
            synchronized (this) {
                for (int i = 0; i < Math.min(total, 100); i++) {
                    eventWindow.addLast(now);
                }
                lastActiveTime = now;
            }
        }
        evaluate();
    }

    // ─────────────────────────────────────────────────────────────────
    //  Video mode — called by AppActivityRecorder every 2s
    // ─────────────────────────────────────────────────────────────────

    /**
     * Set to true when the user is actively on a video streaming site.
     * Extends idle timeout from 2 min to 10 min so video watching
     * doesn't incorrectly mark the user as idle.
     */
    public void setWatchingVideo(boolean watching) {
        if (this.watchingVideo == watching) return;
        this.watchingVideo = watching;
        if (watching) {
            log.info("Video mode ON — idle timeout extended to {} min",
                    VIDEO_IDLE_TIMEOUT_SECS / 60);
        } else {
            log.info("Video mode OFF — idle timeout back to {} min",
                    IDLE_TIMEOUT_SECS / 60);
        }
    }

    public boolean isWatchingVideo() { return watchingVideo; }

    // ─────────────────────────────────────────────────────────────────
    //  External state setters
    // ─────────────────────────────────────────────────────────────────

    public void setAway() {
        systemLocked = true;
        watchingVideo = false;
        applyStatus(UserStatus.AWAY);
    }

    public void setUnlocked() {
        systemLocked   = false;
        lastActiveTime = Instant.now();
        applyStatus(UserStatus.IDLE);
    }

    public void setStopped() {
        applyStatus(UserStatus.STOPPED);
    }

    public UserStatus getStatus()              { return currentStatus; }
    public void setOnStatusChanged(Runnable cb) { this.onStatusChanged = cb; }

    // ─────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────

    private void pruneWindow(Instant now) {
        Instant cutoff = now.minusSeconds(WINDOW_SECS);
        while (!eventWindow.isEmpty()
                && eventWindow.peekFirst().isBefore(cutoff)) {
            eventWindow.pollFirst();
        }
    }

    private void applyStatus(UserStatus newStatus) {
        if (newStatus == currentStatus) return;
        UserStatus prev = currentStatus;
        currentStatus   = newStatus;

        String videoNote = watchingVideo ? " [video mode]" : "";
        log.info("User status: {} → {}{}", prev.getLabel(), newStatus, videoNote);

        if (onStatusChanged != null) {
            try { onStatusChanged.run(); }
            catch (Exception e) { log.debug("onStatusChanged error: {}", e.getMessage()); }
        }
    }
}