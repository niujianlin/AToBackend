package com.ato.account.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * D18：DB 存 UTC，API 输出 ISO8601 UTC（如 2026-10-05T10:49:31Z）。
 */
public final class TimeFormat {

    private static final DateTimeFormatter ISO_UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private TimeFormat() {
    }

    public static String utc(LocalDateTime time) {
        return time == null ? null : ISO_UTC.format(time);
    }
}
