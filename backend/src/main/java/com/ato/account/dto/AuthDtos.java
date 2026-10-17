package com.ato.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 接口出入参定义。全局 Jackson 采用 SNAKE_CASE，字段名与 docs/openapi.yaml 完全一致。
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public static final String PHONE_REGEX = "^1[3-9]\\d{9}$";
    public static final String SCENE_REGEX = "^(login|bind|change_phone)$";

    // ---------------- Requests ----------------

    public record SmsCodeRequest(
            @NotBlank @Pattern(regexp = PHONE_REGEX, message = "phone_number") String phoneNumber,
            @NotBlank @Pattern(regexp = SCENE_REGEX, message = "scene") String scene) {
    }

    public record LoginRequest(
            @NotBlank String authType,
            @NotBlank String authCode,
            String phone) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record LogoutRequest(String refreshToken) {
    }

    public record SessionRevokeRequest(@NotBlank String sessionId) {
    }

    public record ProfileUpdateRequest(
            @Size(max = 32) String nickname,
            @Size(max = 255) String avatar,
            Integer gender,
            @Size(max = 255) String bio) {
    }

    // ---------------- Responses ----------------

    /** access_expire / refresh_expire 是秒数，用 Integer 避免被全局 Long→String 策略转成字符串 */
    public record TokenPair(
            String accessToken,
            String refreshToken,
            Integer accessExpire,
            Integer refreshExpire) {
    }

    public record LoginResponse(
            String accessToken,
            String refreshToken,
            Integer accessExpire,
            Integer refreshExpire,
            UserBrief userInfo) {
    }

    public record UserBrief(
            Long userId,
            String nickname,
            String avatar,
            @JsonProperty("is_new_user") boolean isNewUser) {
    }

    public record SessionItem(
            Long sessionId,
            String deviceId,
            String platform,
            String authType,
            String deviceName,
            String ipAddr,
            String createdAt,
            String updatedAt,
            @JsonProperty("is_current") boolean isCurrent) {
    }

    public record SessionListResponse(int total, List<SessionItem> sessionList) {
    }

    public record UserProfileResponse(
            Long userId,
            String nickname,
            String avatar,
            Integer gender,
            String bio,
            Integer accountStatus,
            String createdAt) {
    }
}
