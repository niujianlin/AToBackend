package com.ato.account.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 请求信息提取。真实 IP 优先取网关注入的 X-Client-Ip，其次 X-Forwarded-For 首段。
 */
public final class RequestUtil {

    private RequestUtil() {
    }

    public static String clientIp(HttpServletRequest request) {
        String ip = trimToNull(request.getHeader("X-Client-Ip"));
        if (ip == null) {
            String forwarded = trimToNull(request.getHeader("X-Forwarded-For"));
            if (forwarded != null) {
                ip = forwarded.split(",")[0].trim();
            }
        }
        if (ip == null) {
            ip = trimToNull(request.getRemoteAddr());
        }
        return ip == null ? "unknown" : ip;
    }

    public static String deviceId(HttpServletRequest request) {
        String deviceId = trimToNull(request.getHeader("X-Device-Id"));
        return deviceId == null ? "unknown" : deviceId;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
