package com.activepulse.agent.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * TimeUtil — timestamp helpers.
 *
 * DB / logs   → IST format:  "2026-03-31 15:31:33"       (human readable)
 * API payload → ISO 8601:    "2026-03-31T09:01:33.000Z"  (server required)
 */
public class TimeUtil {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // IST for DB storage and logs
    private static final DateTimeFormatter DB_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(IST);

    // ISO 8601 UTC for API payloads
    private static final DateTimeFormatter ISO_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(ZoneId.of("UTC"));

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss").withZone(IST);

    private TimeUtil() {}

    /** IST string for DB writes and logs. e.g. "2026-03-31 15:31:33" */
    public static String nowIST() {
        return DB_FORMAT.format(Instant.now());
    }

    /** IST string from Instant — for DB writes. */
    public static String toIST(Instant instant) {
        return DB_FORMAT.format(instant);
    }

    /** ISO 8601 UTC string — use this for API payload fields. */
    public static String toISO(Instant instant) {
        return ISO_FORMAT.format(instant);
    }

    /** ISO 8601 UTC string from an IST DB string — converts DB value to API format. */
    public static String istToISO(String istString) {
        if (istString == null || istString.isBlank()) return null;
        try {
            // Parse IST string back to Instant
            java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(
                    istString,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            Instant instant = ldt.atZone(IST).toInstant();
            return ISO_FORMAT.format(instant);
        } catch (Exception e) {
            return istString; // fallback — return as-is
        }
    }

    /** Human readable IST for display. */
    public static String nowDisplay() {
        return DISPLAY_FORMAT.format(Instant.now());
    }

    /** Formats raw seconds into "Xh Xm Xs". e.g. 3725 → "1h 2m 5s" */
    public static String formatDuration(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return String.format("%dh %dm %ds", h, m, s);
    }

    /** Short format. e.g. 3725 → "1h 2m" */
    public static String formatDurationShort(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m";
        return totalSeconds + "s";
    }
}