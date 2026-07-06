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

    // Productivity rules (v1.0.5)
   private static final long YOUTUBE_IDLE_THRESHOLD_SEC   = 3600; // 1 hour
  private static final long BROWSER_NO_URL_THRESHOLD_SEC = 1800; // 30 minutes


    private static final java.util.Set<String> BROWSER_PROCESSES = java.util.Set.of(
            "chrome.exe", "msedge.exe", "firefox.exe", "brave.exe",
            "opera.exe", "opera_gx.exe", "arc.exe", "vivaldi.exe",
            "chromium.exe", "iexplore.exe"
    );

    private static final java.util.Set<String> YOUTUBE_DOMAINS = java.util.Set.of(
            "youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be"
    );

    private static final java.util.Set<String> YOUTUBE_APP_PROCESSES = java.util.Set.of(
            "youtube.exe",
            "youtubemusic.exe"
    );

    private static boolean isYouTubeUrl(String url) {
        if (url == null || url.isBlank()) return false;
        String lower = url.toLowerCase();
        for (String d : YOUTUBE_DOMAINS) {
            if (lower.contains("://" + d + "/") || lower.contains("://www." + d + "/")
                    || lower.contains("://" + d) || lower.endsWith("://" + d)) {
                return true;
            }
        }
        return false;
    }

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

        String proc = window.processName() == null ? "" : window.processName().trim().toLowerCase();
        String url  = window.url()         == null ? "" : window.url().trim();
        boolean isLockApp = proc.equals("lockapp.exe") || proc.equals("logonui.exe");
        boolean isBrowser = BROWSER_PROCESSES.contains(proc);

        String activityType;
        if (isLockApp) {
            // LockApp/LogonUI always IDLE (existing rule)
            activityType = "IDLE";
        } else {
            UserStatus status = UserStatusTracker.getInstance().getStatus();
            activityType = switch (status) {
                case IDLE   -> "IDLE";
                case AWAY   -> "AWAY";
                case LOCKED -> "AWAY";
                default     -> "ACTIVE";
            };

            // ─── R1: YouTube 1h+ with no input → force IDLE ─────────────
// Applies to BOTH browser tabs on youtube.com AND the YouTube desktop app
            boolean isYouTubeApp = YOUTUBE_APP_PROCESSES.contains(proc);
            boolean isYouTubeInBrowser = isBrowser && isYouTubeUrl(url);

            if ((isYouTubeApp || isYouTubeInBrowser)
                    && seconds >= YOUTUBE_IDLE_THRESHOLD_SEC
                    && !"ACTIVE".equals(activityType)) {
                activityType = "IDLE";
                log.debug("Rule R1: YouTube idle {}s → IDLE (proc={} url={} app={})",
                        seconds, proc, url, isYouTubeApp);
            }

            // ─── R2: Browser open 30min+ with no URL → force AWAY ──────
            if (isBrowser && seconds >= BROWSER_NO_URL_THRESHOLD_SEC
                    && !"ACTIVE".equals(activityType)
                    && url.isEmpty()) {
                activityType = "AWAY";
                log.debug("Rule R2: browser no-URL idle {}s → AWAY (proc={})",
                        seconds, proc);
            }
        }

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
