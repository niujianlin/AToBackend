package com.ato.account.common;

import lombok.Getter;

/**
 * 全量业务错误码（与 docs/openapi.yaml 的 x-error-codes 一一对应）。
 * 约定：HTTP 恒为 200，业务结果看 code；msg 统一英文。
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "success"),

    // ---- 通用 ----
    MISSING_PARAMETER(40003, "Missing parameter"),
    INTERNAL_ERROR(500, "Internal server error"),
    NOT_IMPLEMENTED_IN_MVP(40099, "not implemented in MVP"),

    // ---- 登录 ----
    AUTH_CODE_INVALID(40001, "Authorization verification failed"),
    SMS_CODE_INVALID(40002, "Code expired"),
    ACCOUNT_DEACTIVATED(40004, "This account has been deactivated"),
    ACCOUNT_BANNED(40005, "The account has been banned"),
    ACCOUNT_ABNORMAL(40006, "The account is abnormal"),
    THIRD_PARTY_ERROR(40007, "Third-party API call exception"),
    CREDENTIAL_INVALID(40008, "The credential is invalid, please log in again"),

    // ---- 绑定 / 解绑 ----
    IDENTIFIER_BOUND_BY_OTHER(40020, "This login method has already been bound to another account"),
    ALREADY_BOUND(40021, "The current account has already been bound to this login method"),
    LAST_CREDENTIAL_CANNOT_UNBIND(40030, "The only login method of the account cannot be unbound"),
    CREDENTIAL_NOT_BELONG_TO_USER(40031, "This login method is not bound to the current account"),

    // ---- 注销 / 换绑 ----
    ALREADY_DEACTIVATED(40041, "The account is already in a deactivated state"),
    PHONE_ALREADY_BOUND(40042, "The account has already bound a phone number"),
    CHANGE_PHONE_CODE_INVALID(40061, "Verification code error or expired"),
    NO_PHONE_CREDENTIAL(40062, "The account has not been bound to a phone number"),
    NEW_PHONE_BOUND_BY_OTHER(40063, "The new phone number has been bound to another account"),
    SAME_PHONE(40064, "The new phone number matches the currently bound phone number"),

    // ---- 短信 ----
    PHONE_FORMAT_INVALID(40050, "The phone number format is incorrect"),
    SMS_TOO_FREQUENT(40051, "The request is made too frequently. Please try again later"),
    SMS_SEND_FAILED(40052, "SMS sending failed"),

    // ---- 会话 ----
    SESSION_NOT_FOUND(40070, "The session does not exist or does not belong to the current user"),
    CANNOT_REVOKE_CURRENT_SESSION(40071, "It is not allowed to revoke the current session"),
    SESSION_ALREADY_REVOKED(40072, "This session is already in a revoked state"),
    SESSION_QUERY_FAILED(40073, "Failed to query the session list"),

    // ---- 资料 ----
    PROFILE_FIELD_INVALID(40081, "Invalid profile field"),

    // ---- 认证 ----
    NOT_LOGGED_IN(40100, "Not logged in or invalid token"),
    REFRESH_TOKEN_INVALID(40101, "The refresh_token is invalid. Please log in again"),
    MISSING_REFRESH_TOKEN(40102, "Missing parameter: refresh_token"),
    RT_ACCOUNT_DEACTIVATED(40103, "This account has been deactivated"),
    RT_ACCOUNT_BANNED(40104, "The account has been banned"),
    RT_ACCOUNT_ABNORMAL(40105, "The account is abnormal"),
    MISSING_AUTH_INFO(40106, "Missing authentication information");

    private final int code;
    private final String msg;

    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
