package com.ato.account.controller;

import com.ato.account.common.ApiResult;
import com.ato.account.common.BizException;
import com.ato.account.common.ErrorCode;
import com.ato.account.dto.AuthDtos.LoginRequest;
import com.ato.account.dto.AuthDtos.LoginResponse;
import com.ato.account.dto.AuthDtos.LogoutRequest;
import com.ato.account.dto.AuthDtos.RefreshRequest;
import com.ato.account.dto.AuthDtos.SessionListResponse;
import com.ato.account.dto.AuthDtos.SessionRevokeRequest;
import com.ato.account.dto.AuthDtos.SmsCodeRequest;
import com.ato.account.dto.AuthDtos.TokenPair;
import com.ato.account.service.AuthService;
import com.ato.account.service.TokenService;
import com.ato.account.util.RequestUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * 账号/登录域接口，路径与 docs/openapi.yaml 一致。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final Set<String> PLATFORMS = Set.of("mobile", "pad", "web", "desktop");
    private static final String DEFAULT_PLATFORM = "mobile";

    private final AuthService authService;

    @PostMapping("/sms_code")
    public ApiResult<Void> smsCode(@Valid @RequestBody SmsCodeRequest request, HttpServletRequest httpRequest) {
        authService.sendSmsCode(request, RequestUtil.clientIp(httpRequest), RequestUtil.deviceId(httpRequest));
        return ApiResult.ok();
    }

    @PostMapping("/login")
    public ApiResult<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                          HttpServletRequest httpRequest,
                                          @RequestHeader(value = "Platform", required = false) String platform,
                                          @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
                                          @RequestHeader(value = "X-Device-Name", required = false) String deviceName) {
        TokenService.LoginContext ctx = new TokenService.LoginContext(
                resolvePlatform(platform), deviceId, deviceName, RequestUtil.clientIp(httpRequest));
        return ApiResult.ok(authService.login(request, ctx));
    }

    @PostMapping("/refresh")
    public ApiResult<TokenPair> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResult.ok(authService.refresh(request));
    }

    @PostMapping("/logout")
    public ApiResult<Void> logout(@RequestBody(required = false) LogoutRequest request,
                                  @RequestHeader(value = "Authorization", required = false) String authorization) {
        authService.logout(request, authorization);
        return ApiResult.ok();
    }

    @PostMapping("/logout_cancel")
    public ApiResult<Void> cancelAccount() {
        authService.cancelAccount();
        return ApiResult.ok();
    }

    @GetMapping("/session/list")
    public ApiResult<SessionListResponse> listSessions() {
        return ApiResult.ok(authService.listSessions());
    }

    @PostMapping("/session/revoke")
    public ApiResult<Void> revokeSession(@Valid @RequestBody SessionRevokeRequest request) {
        authService.revokeSession(request);
        return ApiResult.ok();
    }

    // ---- 以下为 MVP 桩接口：契约已冻结，v1.1 实现 ----

    @PostMapping("/bind")
    public ApiResult<Void> bind(@RequestBody(required = false) Map<String, Object> body) {
        authService.bindNotImplemented();
        return ApiResult.ok();
    }

    @PostMapping("/unbind")
    public ApiResult<Void> unbind(@RequestBody(required = false) Map<String, Object> body) {
        authService.unbindNotImplemented();
        return ApiResult.ok();
    }

    @PostMapping("/change_phone")
    public ApiResult<Void> changePhone(@RequestBody(required = false) Map<String, Object> body) {
        authService.changePhoneNotImplemented();
        return ApiResult.ok();
    }

    @PostMapping("/ai_verify")
    public ApiResult<Void> aiVerify() {
        authService.aiVerifyNotImplemented();
        return ApiResult.ok();
    }

    /** 同端互踢依赖 platform，非法值必须拒绝 */
    private String resolvePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return DEFAULT_PLATFORM;
        }
        String normalized = platform.trim().toLowerCase();
        if (!PLATFORMS.contains(normalized)) {
            throw new BizException(ErrorCode.MISSING_PARAMETER, "Platform");
        }
        return normalized;
    }
}
