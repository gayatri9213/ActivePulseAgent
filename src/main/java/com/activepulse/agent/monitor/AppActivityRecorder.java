package com.activepulse.agent.monitor;

import com.activepulse.agent.db.DatabaseManager;
import com.activepulse.agent.monitor.BrowserUrlTracker.UrlResult;
import com.activepulse.agent.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AppActivityRecorder {

    private static final Logger log = LoggerFactory.getLogger(AppActivityRecorder.class);

    private static final int POLL_SECONDS   = 2;
    private static final int MIN_APP_SECS   = 2;
    private static final int URL_FLUSH_SECS = 30;

    private final WindowTracker     windowTracker = new WindowTracker();
    private final BrowserUrlTracker urlTracker    = BrowserUrlTracker.getInstance();

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "app-tracker");
                t.setDaemon(true);
                return t;
            });

    // ── App state ─────────────────────────────────────────────────────
    private WindowInfo currentWindow    = WindowInfo.empty();
    private Instant    windowStart      = Instant.now();
    private String     windowStatusType = "ACTIVE";

    // ── URL state ─────────────────────────────────────────────────────
    private String  currentUrl      = null;
    private String  currentDomain   = null;
    private Instant urlStart        = null;
    private String  urlStatusType   = "ACTIVE";

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile AppActivityRecorder instance;
    private AppActivityRecorder() {}

    public static AppActivityRecorder getInstance() {
        if (instance == null) {
            synchronized (AppActivityRecorder.class) {
                if (instance == null) instance = new AppActivityRecorder();
            }
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────

    public void start() {
        log.info("AppActivityRecorder starting.");
        scheduler.scheduleAtFixedRate(
                this::pollWindow, 0, POLL_SECONDS, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(
                this::flushUrl, URL_FLUSH_SECS, URL_FLUSH_SECS, TimeUnit.SECONDS);
    }

    public void onSystemLocked() {
        log.info("System locked - saving pending activities with AWAY status");
        
        Instant now = Instant.now();
        
        // Save any pending window activity with AWAY status
        if (!currentWindow.isEmpty()) {
            saveActivity(currentWindow, null, windowStart, now, "AWAY");
            // Reset window state
            currentWindow = WindowInfo.empty();
            windowStart = now;
            windowStatusType = "AWAY";
        }
        
        // Save any pending URL activity with AWAY status
        synchronized (this) {
            if (currentUrl != null && urlStart != null) {
                saveActivity(currentWindow, currentUrl, urlStart, now, "AWAY");
                // Reset URL state
                currentUrl = null;
                currentDomain = null;
                urlStart = null;
                urlStatusType = "AWAY";
            }
        }
    }

    public void stop() {
        scheduler.shutdownNow();
        Instant now = Instant.now();
        if (!currentWindow.isEmpty())
            saveActivity(currentWindow, null, windowStart, now, windowStatusType);
        synchronized (this) {
            if (currentUrl != null && urlStart != null)
                saveActivity(currentWindow, currentUrl, urlStart, now, urlStatusType);
        }
        log.info("AppActivityRecorder stopped.");
    }

    // ─────────────────────────────────────────────────────────────────
    //  Window poll — every 2 seconds
    // ─────────────────────────────────────────────────────────────────

    private void pollWindow() {
        if (SystemLockDetector.getInstance().isLocked()) return;
        try {
            WindowInfo active = windowTracker.getActiveWindow();
            if (active.isEmpty()) return;
            Instant now = Instant.now();

            if (!active.isSameWindow(currentWindow)) {
                if (!currentWindow.isEmpty()) {
                    // Use current activity type, not cached status, to ensure correct status during lock
                    String currentActivityType = resolveActivityType();
                    saveActivity(currentWindow, null, windowStart, now, currentActivityType);
                }

                if (!active.isSameApp(currentWindow)) {
                    synchronized (this) {
                        if (currentUrl != null && urlStart != null) {
                            // Use current activity type, not cached status, to ensure correct status during lock
                            String currentActivityType = resolveActivityType();
                            saveActivity(currentWindow, currentUrl, urlStart, now, currentActivityType);
                        }
                        currentUrl = null; currentDomain = null; urlStart = null;
                    }
                }
                log.info("Window → app='{}' title='{}'",
                        active.appName(), truncate(active.windowTitle(), 70));
                currentWindow    = active;
                windowStart      = now;
                windowStatusType = resolveActivityType(); // capture status at START
                
                // Check if we need to split multi-day away sessions when activity resumes
                AppConfigManager.getInstance().splitMultiDayAwaySession();
            }
            pollUrl(active, now);
        } catch (Exception e) {
            log.warn("pollWindow error: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  URL poll — called every 2s while a browser is active
    // ─────────────────────────────────────────────────────────────────

    private synchronized void pollUrl(WindowInfo active, Instant now) {
        UrlResult result = urlTracker.getActiveUrl(active.appName());
        log.debug("URL poll — app='{}' result={}",
                active.appName(), result != null ? result.domain() : "null");

        if (result == null) {
            UserStatusTracker.getInstance().setWatchingVideo(false);
            return;
        }

        boolean isVideo = VideoSiteDetector.getInstance().isVideoSite(result.domain());
        UserStatusTracker.getInstance().setWatchingVideo(isVideo);

        String newUrl = result.url();

        if (currentUrl == null) {
            log.info("URL started → {} | {}", result.domain(), truncate(newUrl, 80));
            currentUrl      = newUrl;
            currentDomain   = result.domain();
            urlStart        = now;
            urlStatusType   = resolveActivityType(); // capture status at URL START
        } else if (!currentUrl.equals(newUrl)) {
            // Use current activity type, not cached status, to ensure correct status during lock
            String currentActivityType = resolveActivityType();
            saveActivity(active, currentUrl, urlStart, now, currentActivityType);
            log.info("URL → {} | {} [{}]", result.domain(), truncate(newUrl, 80), currentActivityType);
            currentUrl      = newUrl;
            currentDomain   = result.domain();
            urlStart        = now;
            urlStatusType   = currentActivityType;
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Periodic URL flush — every 30s
    // ─────────────────────────────────────────────────────────────────

    private synchronized void flushUrl() {
        if (currentUrl == null || urlStart == null) return;
        
        Instant now = Instant.now();
        
        // Always use current activity type, not cached status, to ensure correct status during lock
        String currentActivityType = resolveActivityType();
        
        log.info("URL flush → {} {}s [{}]", currentDomain,
                now.getEpochSecond() - urlStart.getEpochSecond(), currentActivityType);
        saveActivity(currentWindow, currentUrl, urlStart, now, currentActivityType);
        urlStart      = now;
        urlStatusType = currentActivityType;
    }

    // ─────────────────────────────────────────────────────────────────
    //  Resolve activityType from current tray status
    // ─────────────────────────────────────────────────────────────────

    private String resolveActivityType() {
        // If system is locked, always return AWAY regardless of user status
        if (SystemLockDetector.getInstance().isLocked()) {
            return "AWAY";
        }
        
        return switch (UserStatusTracker.getInstance().getStatus()) {
            case WORKING, NEUTRAL -> "ACTIVE";
            case IDLE             -> "IDLE";
            case AWAY, STOPPED    -> "AWAY";
        };
    }

    // ─────────────────────────────────────────────────────────────────
    //  DB write — unified for apps and URLs
    // ─────────────────────────────────────────────────────────────────

    private void saveActivity(WindowInfo window, String url,
                              Instant start, Instant end,
                              String activityType) {
        long duration = end.getEpochSecond() - start.getEpochSecond();
        if (url == null && duration < MIN_APP_SECS) return;

        String username = AppConfigManager.getInstance().getUsername();
        String deviceid = AppConfigManager.getInstance().getDeviceid();

        // Check if activity spans multiple days and split if necessary
        String startTimeStr = TimeUtil.toIST(start);
        String endTimeStr = TimeUtil.toIST(end);
        String startDate = startTimeStr.split(" ")[0];
        String endDate = endTimeStr.split(" ")[0];
        
        if (!startDate.equals(endDate)) {
            // Activity spans multiple days, split it
            saveMultiDayActivity(window, url, start, end, activityType, startDate, endDate);
            return;
        }

        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO activity_log
                        (username, deviceid, starttime, endtime,
                         processname, title, url, duration, activity_type)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                ps.setString(1, username);
                ps.setString(2, deviceid);
                ps.setString(3, startTimeStr);
                ps.setString(4, endTimeStr);
                ps.setString(5, window.appName());
                ps.setString(6, window.windowTitle());
                ps.setString(7, url);
                ps.setLong(8,   duration);
                ps.setString(9, activityType);
                ps.executeUpdate();

                if (url != null)
                    log.info("Saved URL  → {} {}s [{}]", currentDomain, duration, activityType);
                else
                    log.info("Saved app  → '{}' {}s [{}]", window.appName(), duration, activityType);
            }
        } catch (SQLException e) {
            log.error("saveActivity failed: {}", e.getMessage());
        }
    }
    
    /**
     * Splits activity that spans multiple days into separate daily entries.
     */
    private void saveMultiDayActivity(WindowInfo window, String url,
                                     Instant start, Instant end, String activityType,
                                     String startDate, String endDate) {
        String username = AppConfigManager.getInstance().getUsername();
        String deviceid = AppConfigManager.getInstance().getDeviceid();
        
        try {
            Connection conn = DatabaseManager.getInstance().getConnection();
            
            // Create entries for each day
            LocalDate current = LocalDate.parse(startDate);
            LocalDate endLocal = LocalDate.parse(endDate);
            
            while (!current.isAfter(endLocal)) {
                String dayStart = current.atStartOfDay().toString().replace("T", " ") + ":00";
                String dayEnd = current.atTime(23, 59, 59).toString().replace("T", " ") + ":59";
                
                Instant periodStart = current.equals(LocalDate.parse(startDate)) 
                    ? start 
                    : current.atStartOfDay().atZone(java.time.ZoneId.systemDefault()).toInstant();
                
                Instant periodEnd = current.equals(endLocal) 
                    ? end 
                    : current.atTime(23, 59, 59).atZone(java.time.ZoneId.systemDefault()).toInstant();
                
                long periodDuration = periodEnd.getEpochSecond() - periodStart.getEpochSecond();
                
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO activity_log
                            (username, deviceid, starttime, endtime,
                             processname, title, url, duration, activity_type)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    ps.setString(1, username);
                    ps.setString(2, deviceid);
                    ps.setString(3, TimeUtil.toIST(periodStart));
                    ps.setString(4, TimeUtil.toIST(periodEnd));
                    ps.setString(5, window.appName());
                    ps.setString(6, window.windowTitle());
                    ps.setString(7, url);
                    ps.setLong(8,   periodDuration);
                    ps.setString(9, activityType);
                    ps.executeUpdate();
                    
                    log.info("Split multi-day activity for {} → {} {}s [{}]", 
                            current, url != null ? currentDomain : window.appName(), 
                            periodDuration, activityType);
                }
                
                current = current.plusDays(1);
            }
        } catch (SQLException e) {
            log.error("saveMultiDayActivity failed: {}", e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        return (s == null || s.length() <= max) ? s : s.substring(0, max) + "...";
    }
}