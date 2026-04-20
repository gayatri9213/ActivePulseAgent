package com.activepulse.agent.util;

import java.time.*;
import java.time.format.DateTimeFormatter;

/**
 * Timestamp helpers.
 *   - nowIST()        — yyyy-MM-dd HH:mm:ss in Asia/Kolkata, used for DB rows
 *   - nowUtcIso()     — ISO 8601 UTC, used for API payloads
 *   - todayIST()      — yyyy-MM-dd in Asia/Kolkata
 */
public final class TimeUtil {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final DateTimeFormatter IST_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private TimeUtil() {}

    public static String nowIST() {
        return LocalDateTime.now(IST).format(IST_FMT);
    }

    public static String todayIST() {
        return LocalDate.now(IST).format(DATE_FMT);
    }

    public static String nowUtcIso() {
        return OffsetDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    public static LocalDateTime parseIST(String s) {
        return LocalDateTime.parse(s, IST_FMT);
    }

    public static long secondsBetween(String startIST, String endIST) {
        LocalDateTime a = parseIST(startIST);
        LocalDateTime b = parseIST(endIST);
        return Duration.between(a, b).getSeconds();
    }
}
