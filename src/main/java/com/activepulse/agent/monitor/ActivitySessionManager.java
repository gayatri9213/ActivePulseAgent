package com.activepulse.agent.monitor;

import com.activepulse.agent.db.ActivityDao;
import com.activepulse.agent.util.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * Maintains the current window-focus "session" and flushes an activity_log row
 * each time focus changes (or at midnight for day splits).
 *
 * IDLE DETECTION FIX (this revision):
 *   Previous version used (lastInputAtMs <= sessionStartLastInputMs) which was
 *   BROKEN — the action that ends a session (Alt-Tab, mouse click) is itself
 *   an input event, so lastInputAtMs advances and the check fails even when
 *   the user was actually idle the whole time.
 *
 *   New approach: snapshot the monotonic totalInputs count at session start.
 *   At flush, delta = current - start. If delta is small (<= INPUT_TOLERANCE),
 *   the user was effectively idle during the session, and the tolerance
 *   swallows the flush-trigger event.
 *
 * IDENTIFIER LOGIC (stored as "app" column):
 *   1. YouTube session with URL          -> full URL
 *   2. YouTube session without URL       -> "youtube.com | <video title>" from title
 *   3. YouTube session, no useful title  -> process name
 *   4. Other browser tab with URL        -> domain
 *   5. Known desktop app (map)           -> mapped domain
 *   6. Blank / unresolvable process      -> "System Process"
 *   7. Everything else                   -> process name
 *
 * ACTIVITY TYPE RULES:
 *   R1: YouTube session with <= 5 input events for 1h+ -> IDLE
 *   R2: Browser open 30min+ with no URL                -> AWAY
 *   R3: Non-passive app, user IDLE/AWAY, 5min+         -> IDLE
 */
public final class ActivitySessionManager {

    private static final Logger log = LoggerFactory.getLogger(ActivitySessionManager.class);
    private static final ActivitySessionManager INSTANCE = new ActivitySessionManager();

    private ActiveWindowInfo currentWindow;
    private LocalDateTime    sessionStart;
    private long             sessionStartLastInputMs;
    private long             sessionStartTotalInputs;   // NEW: snapshot of KMT.getTotalInputs()

    private static final int  FAST_IDLE_THRESHOLD_SEC      = 300;
    private static final long YOUTUBE_IDLE_THRESHOLD_SEC   = 3600; //3600
    private static final long BROWSER_NO_URL_THRESHOLD_SEC = 1800; //1800

    /**
     * Two-pronged idle detection:
     *
     * A) Absolute tolerance — very few events regardless of session length.
     *    Handles short sessions where the flush trigger dominates the count.
     *
     * B) Rate threshold — events-per-second below this = effectively idle.
     *    Handles long sessions where ambient noise (hover events on YouTube
     *    controls, cursor jitter, autoplay-related events) can accumulate
     *    beyond the absolute tolerance but is still far below real activity.
     *
     * Real "active" work: typing = 3-5 events/sec.
     * Real "idle" watching: 0.01-0.03 events/sec (occasional ambient noise).
     * Threshold set at 0.05 events/sec (3 events per minute).
     */
    private static final int    INPUT_TOLERANCE_ABSOLUTE  = 10;
    private static final double INPUT_RATE_IDLE_THRESHOLD = 0.05;

    /** Fallback identifier when the Windows API returns no process name. */
    private static final String UNKNOWN_PROCESS_LABEL = "System Process";

    private static final Set<String> BROWSER_PROCESSES = Set.of(
            "chrome.exe", "msedge.exe", "firefox.exe", "brave.exe",
            "opera.exe", "opera_gx.exe", "arc.exe", "vivaldi.exe",
            "chromium.exe", "iexplore.exe"
    );

    private static final Set<String> YOUTUBE_DOMAINS = Set.of(
            "youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be"
    );

    private static final Set<String> YOUTUBE_APP_PROCESSES = Set.of(
            "youtube.exe",
            "youtubemusic.exe"
    );

    private static final Set<String> PASSIVE_ALLOWED_APPS = Set.of(
            "teams", "zoom", "webex", "gotomeeting", "skype", "discord", "slack",
            "meet", "hangouts",
            "spotify", "vlc", "wmplayer", "mpc-hc", "itunes", "applemusic",
            "youtubemusic", "netflix", "primevideo", "hotstar", "disneyplus"
    );

    private static final Map<String, String> APP_TO_DOMAIN = Map.ofEntries(
            Map.entry("netflix",       "netflix.com"),
            Map.entry("primevideo",    "primevideo.com"),
            Map.entry("hotstar",       "hotstar.com"),
            Map.entry("disneyplus",    "disneyplus.com"),
            Map.entry("spotify",       "spotify.com"),
            Map.entry("applemusic",    "music.apple.com"),
            Map.entry("itunes",        "apple.com"),
            Map.entry("ms-teams",      "teams.microsoft.com"),
            Map.entry("teams",         "teams.microsoft.com"),
            Map.entry("zoom",          "zoom.us"),
            Map.entry("webex",         "webex.com"),
            Map.entry("slack",         "slack.com"),
            Map.entry("discord",       "discord.com"),
            Map.entry("whatsapp",      "web.whatsapp.com"),
            Map.entry("notion",        "notion.so"),
            Map.entry("gmail",         "mail.google.com"),
            Map.entry("outlook",       "outlook.office.com")
    );

    // ═════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════

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

    private static boolean isPassiveAllowedApp(String processName) {
        if (processName == null || processName.isBlank()) return false;
        String lower = processName.toLowerCase();
        if (lower.endsWith(".exe")) lower = lower.substring(0, lower.length() - 4);
        for (String allowed : PASSIVE_ALLOWED_APPS) {
            if (lower.contains(allowed)) return true;
        }
        return false;
    }

    private static boolean isYouTubeSession(String proc, String url, String title, boolean isBrowser) {
        if (YOUTUBE_APP_PROCESSES.contains(proc)) return true;
        if (isBrowser && isYouTubeUrl(url)) return true;
        if (title != null) {
            String t = title.toLowerCase();
            if (t.contains(" - youtube") || t.endsWith("- youtube")
                    || t.contains(" - youtube music") || t.equals("youtube")
                    || t.equals("youtube music")) {
                return true;
            }
        }
        return false;
    }

    private static String extractDomain(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            String u = url.trim();
            int protoEnd = u.indexOf("://");
            if (protoEnd > 0) u = u.substring(protoEnd + 3);
            int end = u.length();
            for (int i = 0; i < u.length(); i++) {
                char c = u.charAt(i);
                if (c == '/' || c == '?' || c == '#') { end = i; break; }
            }
            String host = u.substring(0, end);
            int colon = host.indexOf(':');
            if (colon > 0) host = host.substring(0, colon);
            if (host.startsWith("www.")) host = host.substring(4);
            return host.isBlank() ? null : host;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String appToDomain(String processName) {
        if (processName == null || processName.isBlank()) return null;
        String lower = processName.toLowerCase();
        if (lower.endsWith(".exe")) lower = lower.substring(0, lower.length() - 4);
        for (Map.Entry<String, String> e : APP_TO_DOMAIN.entrySet()) {
            if (lower.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    private static String youtubeIdentifierFromTitle(String title) {
        if (title == null || title.isBlank()) return null;
        String t = title.trim();
        String lower = t.toLowerCase();

        if (lower.endsWith("- youtube music") || lower.equals("youtube music")) {
            String videoTitle = t.replaceAll("(?i)\\s*-\\s*youtube music\\s*$", "").trim();
            if (videoTitle.isBlank() || videoTitle.equalsIgnoreCase("youtube music")) {
                return "music.youtube.com";
            }
            return "music.youtube.com | " + videoTitle;
        }

        if (lower.endsWith("- youtube") || lower.equals("youtube")) {
            String videoTitle = t.replaceAll("(?i)\\s*-\\s*youtube\\s*$", "").trim();
            if (videoTitle.isBlank() || videoTitle.equalsIgnoreCase("youtube")) {
                return "youtube.com";
            }
            return "youtube.com | " + videoTitle;
        }

        return null;
    }

    private static String safeProcessName(String raw) {
        if (raw == null) return UNKNOWN_PROCESS_LABEL;
        String trimmed = raw.trim();
        if (trimmed.isBlank()) return UNKNOWN_PROCESS_LABEL;
        String lower = trimmed.toLowerCase();
        if (lower.equals("unknown") || lower.equals("unknown app")
                || lower.equals("other") || lower.equals("(other)")
                || lower.equals("n/a")   || lower.equals("null")) {
            return UNKNOWN_PROCESS_LABEL;
        }
        return trimmed;
    }

    // ═════════════════════════════════════════════════════════════════

    private ActivitySessionManager() {}

    public static ActivitySessionManager getInstance() { return INSTANCE; }

    public synchronized void update(ActiveWindowInfo next) {
        if (next == null) next = ActiveWindowInfo.unknown();
        LocalDateTime now = LocalDateTime.now(TimeUtil.IST);

        if (currentWindow == null) {
            currentWindow = next;
            sessionStart  = now;
            snapshotInputBaseline();
            return;
        }

        if (!now.toLocalDate().equals(sessionStart.toLocalDate())) {
            LocalDateTime endOfPrev = sessionStart.toLocalDate().atTime(23, 59, 59);
            flush(currentWindow, sessionStart, endOfPrev);
            LocalDateTime startOfNew = now.toLocalDate().atStartOfDay();
            sessionStart = startOfNew;
            snapshotInputBaseline();
        }

        if (currentWindow.isSame(next)) return;

        flush(currentWindow, sessionStart, now);
        currentWindow = next;
        sessionStart  = now;
        snapshotInputBaseline();
    }

    /** Called whenever a new session begins. Records baseline counters. */
    private void snapshotInputBaseline() {
        KeyboardMouseTracker kmt = KeyboardMouseTracker.getInstance();
        sessionStartLastInputMs = kmt.getLastInputAtMs();
        sessionStartTotalInputs = kmt.getTotalInputs();
    }

    public synchronized void flushCurrent() {
        if (currentWindow == null || sessionStart == null) return;
        LocalDateTime now = LocalDateTime.now(TimeUtil.IST);
        flush(currentWindow, sessionStart, now);
        currentWindow = null;
        sessionStart  = null;
    }

    private void flush(ActiveWindowInfo window, LocalDateTime start, LocalDateTime end) {
        long seconds = Math.max(0, Duration.between(start, end).getSeconds());
        if (seconds < 1) return;

        String rawProc  = window.processName();
        String rawTitle = window.title();
        String rawUrl   = window.url();

        String proc  = rawProc  == null ? "" : rawProc.trim().toLowerCase();
        String url   = rawUrl   == null ? "" : rawUrl.trim();
        String title = rawTitle == null ? "" : rawTitle.trim();

        String safeProc = safeProcessName(rawProc);

        boolean isLockApp = proc.equals("lockapp.exe") || proc.equals("logonui.exe");
        boolean isBrowser = BROWSER_PROCESSES.contains(proc);

        // ─── Compute idle metrics ────────────────────────────────────
        KeyboardMouseTracker kmt = KeyboardMouseTracker.getInstance();
        long lastInputMs   = kmt.getLastInputAtMs();
        long nowMs         = System.currentTimeMillis();
        long secondsSinceLastInput = Math.max(0, (nowMs - lastInputMs) / 1000);

        long currentTotalInputs = kmt.getTotalInputs();
        long inputsDuringSession = Math.max(0, currentTotalInputs - sessionStartTotalInputs);
        double inputsPerSec = seconds > 0 ? (double) inputsDuringSession / seconds : 0.0;

        // Idle if (a) very few events overall OR (b) events-per-sec below threshold.
        // (a) catches short sessions dominated by the flush trigger.
        // (b) catches long sessions with ambient noise (video hover, cursor jitter).
        boolean noInputDuringSession =
                (inputsDuringSession <= INPUT_TOLERANCE_ABSOLUTE)
                        || (inputsPerSec < INPUT_RATE_IDLE_THRESHOLD);

        // ─── Determine activity_type ─────────────────────────────────
        String activityType;
        UserStatus status = null;
        if (isLockApp) {
            activityType = "IDLE";
        } else {
            status = UserStatusTracker.getInstance().getStatus();
            activityType = switch (status) {
                case IDLE   -> "IDLE";
                case AWAY   -> "AWAY";
                case LOCKED -> "AWAY";
                default     -> "ACTIVE";
            };

            // R1: YouTube foreground for threshold+ with <= INPUT_TOLERANCE events -> IDLE
            boolean isYouTube = isYouTubeSession(proc, url, title, isBrowser);
            if (isYouTube
                    && seconds >= YOUTUBE_IDLE_THRESHOLD_SEC
                    && noInputDuringSession) {
                activityType = "IDLE";
                log.info("Rule R1: YouTube {}s foreground with {} input events "
                                + "(rate={} evt/sec, tol_abs={}, tol_rate={}) -> IDLE "
                                + "(proc={} url={} title={})",
                        seconds, inputsDuringSession,
                        String.format("%.4f", inputsPerSec),
                        INPUT_TOLERANCE_ABSOLUTE, INPUT_RATE_IDLE_THRESHOLD,
                        proc, url, title);
            }

            // R2: Browser open threshold+ with no URL AND low input rate -> AWAY
            //
            // We check inputsPerSec instead of UserStatusTracker's status because:
            //   - The old check (!"ACTIVE".equals) required UserStatusTracker to
            //     already report IDLE/AWAY, which needs a longer idle timeout than
            //     R2 itself. That double-gate made R2 skip legit cases.
            //   - The rate check is the same one R1 uses (INPUT_RATE_IDLE_THRESHOLD).
            //     Low rate + empty URL + long session = user isn't productively
            //     browsing, mark AWAY.
            if (isBrowser
                    && seconds >= BROWSER_NO_URL_THRESHOLD_SEC
                    && url.isEmpty()
                    && inputsPerSec < INPUT_RATE_IDLE_THRESHOLD) {
                activityType = "AWAY";
                log.info("Rule R2: browser {}s no-URL with rate={} evt/sec "
                                + "(threshold={}) -> AWAY (proc={} title={})",
                        seconds, String.format("%.4f", inputsPerSec),
                        INPUT_RATE_IDLE_THRESHOLD, proc, title);
            }

            // R3: Fast-idle for non-passive, non-browser apps
            boolean isPassiveApp = isPassiveAllowedApp(proc);
            boolean userWasInactive = (status == UserStatus.IDLE || status == UserStatus.AWAY);
            if (seconds >= FAST_IDLE_THRESHOLD_SEC
                    && userWasInactive
                    && !isBrowser
                    && !isPassiveApp
                    && !"IDLE".equals(activityType)
                    && !"AWAY".equals(activityType)) {
                log.debug("Rule R3: non-passive app {} foreground {}s while user inactive -> IDLE",
                        proc, seconds);
                activityType = "IDLE";
            }
        }

        // ─── Decide the identifier stored as the "app" column ────────
        String appIdentifier;

        if (isYouTubeSession(proc, url, title, isBrowser)) {
            if (!url.isBlank()) {
                appIdentifier = url;
            } else {
                String titleId = youtubeIdentifierFromTitle(title);
                appIdentifier = (titleId != null) ? titleId : safeProc;
            }
        } else if (isBrowser && !url.isBlank()) {
            String domain = extractDomain(url);
            appIdentifier = (domain != null) ? domain : safeProc;
        } else {
            String mapped = appToDomain(proc);
            appIdentifier = (mapped != null) ? mapped : safeProc;
        }

        ActivityDao.insert(
                fmt(start), fmt(end),
                appIdentifier, safeTitle(rawTitle), url,
                seconds, activityType);

        log.info("Flushed activity: identifier={} (proc={}, url={}, title={}) "
                        + "[{}->{}] {}s {} inputs={} rate={}/sec secSinceInput={}",
                appIdentifier, safeProc, url, title,
                fmt(start), fmt(end), seconds, activityType,
                inputsDuringSession, String.format("%.4f", inputsPerSec),
                secondsSinceLastInput);
    }

    private static String safeTitle(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static String fmt(LocalDateTime t) {
        return t.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}