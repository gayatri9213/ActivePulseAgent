package com.activepulse.agent.monitor;

import com.activepulse.agent.db.ActivityDao;
import com.activepulse.agent.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Maintains the current window-focus "session" and flushes an activity_log row
 * each time focus changes (or at midnight for day splits).
 *
 * Called periodically by ActivityJob.
 */
public final class ActivitySessionManager {

    private static final Logger log = LoggerFactory.getLogger(ActivitySessionManager.class);
    private static final ActivitySessionManager INSTANCE = new ActivitySessionManager();

    private ActiveWindowInfo currentWindow;
    private LocalDateTime    sessionStart;

    private ActivitySessionManager() {}

    public static ActivitySessionManager getInstance() { return INSTANCE; }

    public synchronized void update(ActiveWindowInfo next) {
        if (next == null) next = ActiveWindowInfo.unknown();
        LocalDateTime now = LocalDateTime.now(TimeUtil.IST);

        // First sample
        if (currentWindow == null) {
            currentWindow = next;
            sessionStart  = now;
            return;
        }

        // Midnight split: flush the old row at 23:59:59, start fresh at 00:00:00
        if (!now.toLocalDate().equals(sessionStart.toLocalDate())) {
            LocalDateTime endOfPrev = sessionStart.toLocalDate().atTime(23, 59, 59);
            flush(currentWindow, sessionStart, endOfPrev);
            LocalDateTime startOfNew = now.toLocalDate().atStartOfDay();
            sessionStart = startOfNew;
        }

        // Same window → keep running
        if (currentWindow.isSame(next)) return;

        // Focus changed → flush previous session
        flush(currentWindow, sessionStart, now);
        currentWindow = next;
        sessionStart  = now;
    }

    /**
     * Flush any in-progress session to the DB (called on shutdown).
     */
    public synchronized void flushCurrent() {
        if (currentWindow == null || sessionStart == null) return;
        LocalDateTime now = LocalDateTime.now(TimeUtil.IST);
        flush(currentWindow, sessionStart, now);
        currentWindow = null;
        sessionStart  = null;
    }

    private void flush(ActiveWindowInfo window, LocalDateTime start, LocalDateTime end) {
        long seconds = Math.max(0, Duration.between(start, end).getSeconds());
        if (seconds < 1) return; // ignore sub-second flips

        UserStatus status = UserStatusTracker.getInstance().getStatus();
        String activityType = switch (status) {
            case IDLE   -> "IDLE";
            case AWAY   -> "AWAY";
            case LOCKED -> "AWAY";   // server doesn't accept LOCKED; map to AWAY
            default     -> "ACTIVE"; // ACTIVE and any unknown state
        };

        ActivityDao.insert(
                fmt(start), fmt(end),
                window.processName(), window.title(), window.url(),
                seconds, activityType);

        log.debug("Flushed activity: {} [{}→{}] {}s {}",
                window.processName(), fmt(start), fmt(end), seconds, activityType);
    }

    private static String fmt(LocalDateTime t) {
        return t.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
