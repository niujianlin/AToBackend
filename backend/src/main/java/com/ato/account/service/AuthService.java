package com.ato.account.service;

import com.ato.account.common.BizException;
import com.ato.account.common.ErrorCode;
import com.ato.account.context.UserContext;
import com.ato.account.context.UserContext.LoginUser;
import com.ato.account.dto.AuthDtos.LoginRequest;
import com.ato.account.dto.AuthDtos.LoginResponse;
import com.ato.account.dto.AuthDtos.LogoutRequest;
import com.ato.account.dto.AuthDtos.RefreshRequest;
import com.ato.account.dto.AuthDtos.SessionItem;
import com.ato.account.dto.AuthDtos.SessionListResponse;
import com.ato.account.dto.AuthDtos.SessionRevokeRequest;
import com.ato.account.dto.AuthDtos.SmsCodeRequest;
import com.ato.account.dto.AuthDtos.TokenPair;
import com.ato.account.dto.AuthDtos.UserBrief;
import com.ato.account.entity.AuthCredential;
import com.ato.account.entity.AuthSession;
import com.ato.account.entity.UserInfo;
import com.ato.account.mapper.AuthCredentialMapper;
import com.ato.account.mapper.AuthSessionMapper;
import com.ato.account.mapper.UserInfoMapper;
import com.ato.account.util.CryptoUtil;
import com.ato.account.util.JwtUtil;
import com.ato.account.util.TimeFormat;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号/登录域主流程。MVP 只实现手机号验证码登录；
 * wechat / apple 走 NOT_IMPLEMENTED_IN_MVP 桩，契约与表结构不变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String SCENE_LOGIN = "login";
    private static final String BEARER_PREFIX = "Bearer ";

    private final SmsService smsService;
    private final TokenService tokenService;
    private final RegistrationService registrationService;
    private final CryptoUtil cryptoUtil;
    private final JwtUtil jwtUtil;
    private final UserInfoMapper userInfoMapper;
    private final AuthCredentialMapper authCredentialMapper;
    private final AuthSessionMapper authSessionMapper;

    // ---------------- 1. 下发验证码 ----------------

    public void sendSmsCode(SmsCodeRequest request, String ip, String deviceId) {
        smsService.sendCode(request.phoneNumber(), request.scene(), ip, deviceId);
    }

    // ---------------- 2. 手机号登录（含自动注册） ----------------

    @Transactional(rollbackFor = Exception.class)
    public LoginResponse login(LoginRequest request, TokenService.LoginContext ctx) {
        if (!AuthCredential.TYPE_PHONE.equals(request.authType())) {
            // v1.1 实现：后端用 auth_code 换 unionid/sub，写入同一张 auth_credential
            throw new BizException(ErrorCode.NOT_IMPLEMENTED_IN_MVP, "auth_type=" + request.authType());
        }
        if (request.phone() == null || request.phone().isBlank()) {
            throw new BizException(ErrorCode.MISSING_PARAMETER, "phone");
        }
        String phone = request.phone().trim();

        // 凭证优先：先验验证码，通过后再判账号状态（D14，避免账号枚举）
        smsService.verifyAndConsume(phone, SCENE_LOGIN, request.authCode());

        String identifierHash = cryptoUtil.identifierHash(AuthCredential.TYPE_PHONE, phone);
        AuthCredential credential = authCredentialMapper
                .selectByIdentifierHash(AuthCredential.TYPE_PHONE, identifierHash);

        boolean isNewUser = false;
        if (credential == null) {
            credential = registerIdempotently(phone, identifierHash);
            isNewUser = true;
        } else {
            requireValidCredential(credential);
            tokenService.checkAccountStatus(credential.getUserId(), false);
        }

        TokenPair pair = tokenService.loginAndIssueSession(
                credential.getUserId(), credential.getId(), AuthCredential.TYPE_PHONE, ctx);

        UserInfo user = userInfoMapper.selectById(credential.getUserId());
        UserBrief brief = new UserBrief(
                credential.getUserId(),
                user == null ? "" : user.getNickname(),
                user == null ? "" : user.getAvatar(),
                isNewUser);
        return new LoginResponse(pair.accessToken(), pair.refreshToken(),
                pair.accessExpire(), pair.refreshExpire(), brief);
    }

    /** 并发注册同一手机号：唯一索引兜底，冲突后重查并按老用户继续 */
    private AuthCredential registerIdempotently(String phone, String identifierHash) {
        try {
            return registrationService.registerPhoneUser(phone);
        } catch (DuplicateKeyException e) {
            log.info("concurrent registration detected, fallback to existing credential");
            AuthCredential existing = authCredentialMapper
                    .selectByIdentifierHash(AuthCredential.TYPE_PHONE, identifierHash);
            if (existing == null) {
                throw new BizException(ErrorCode.CREDENTIAL_INVALID);
            }
            requireValidCredential(existing);
            tokenService.checkAccountStatus(existing.getUserId(), false);
            return existing;
        }
    }

    private void requireValidCredential(AuthCredential credential) {
        if (credential.getIsInvalid() != null && credential.getIsInvalid() == AuthCredential.INVALID) {
            throw new BizException(ErrorCode.CREDENTIAL_INVALID);
        }
    }

    // ---------------- 3. 刷新 Token ----------------

    public TokenPair refresh(RefreshRequest request) {
        if (request.refreshToken() == null || request.refreshToken().isBlank()) {
            throw new BizException(ErrorCode.MISSING_REFRESH_TOKEN);
        }
        return tokenService.refresh(request.refreshToken().trim());
    }

    // ---------------- 4. 登出 ----------------

    /**
     * D7：Header 与 body 至少提供一个；两者都无 → 40106；
     * 提供了但无效/过期 → 仍返回成功（幂等登出）。
     */
    public void logout(LogoutRequest request, String authorizationHeader) {
        boolean hasHeader = authorizationHeader != null && !authorizationHeader.isBlank();
        boolean hasBody = request != null && request.refreshToken() != null && !request.refreshToken().isBlank();
        if (!hasHeader && !hasBody) {
            throw new BizException(ErrorCode.MISSING_AUTH_INFO);
        }

        Long sessionId = null;
        if (hasHeader && authorizationHeader.startsWith(BEARER_PREFIX)) {
            sessionId = resolveSessionIdQuietly(authorizationHeader.substring(BEARER_PREFIX.length()).trim());
        }
        if (sessionId == null && hasBody) {
            AuthSession session = authSessionMapper.selectByRefreshHash(CryptoUtil.sha256Hex(request.refreshToken()));
            sessionId = session == null ? null : session.getId();
        }
        if (sessionId == null) {
            log.info("logout with unusable credential, respond success for idempotency");
            return;
        }
        tokenService.revokeSession(sessionId);
    }

    private Long resolveSessionIdQuietly(String accessToken) {
        try {
            Claims claims = jwtUtil.parse(accessToken);
            return Long.parseLong(String.valueOf(claims.get(JwtUtil.CLAIM_SESSION_ID)));
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 5. 账号注销 ----------------

    @Transactional(rollbackFor = Exception.class)
    public void cancelAccount() {
        Long userId = UserContext.get().userId();
        UserInfo user = userInfoMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_LOGGED_IN, "user not found");
        }
        if (user.getAccountStatus() != null && user.getAccountStatus() == UserInfo.STATUS_DEACTIVATED) {
            throw new BizException(ErrorCode.ALREADY_DEACTIVATED);
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        user.setAccountStatus(UserInfo.STATUS_DEACTIVATED);
        user.setDeletedAt(now);
        user.setUpdatedAt(now);
        userInfoMapper.updateById(user);

        // D4：凭证软删并释放 identifier，手机号可被重新注册
        List<AuthCredential> credentials = authCredentialMapper.listValidByUser(userId);
        for (AuthCredential credential : credentials) {
            authCredentialMapper.softDeleteAndRelease(credential.getId(),
                    cryptoUtil.deadIdentifierHash(credential.getId()));
        }

        int revoked = tokenService.revokeAllSessions(userId);
        log.info("account deactivated. userId={}, credentials released={}, sessions revoked={}",
                userId, credentials.size(), revoked);
    }

    // ---------------- 6. 登录设备列表 ----------------

    public SessionListResponse listSessions() {
        LoginUser me = UserContext.get();
        List<AuthSession> actives = authSessionMapper.listActiveByUser(me.userId(), LocalDateTime.now(ZoneOffset.UTC));

        // 同端理论只有 1 条有效会话；仍按 device_id 去重防御，保留 updated_at 最新（已按 desc 排序）
        Map<String, AuthSession> deduped = new LinkedHashMap<>();
        for (AuthSession session : actives) {
            String key = (session.getDeviceId() == null || session.getDeviceId().isEmpty())
                    ? "sid:" + session.getId()
                    : session.getDeviceId();
            deduped.putIfAbsent(key, session);
        }

        List<SessionItem> items = new ArrayList<>(deduped.size());
        for (AuthSession session : deduped.values()) {
            items.add(new SessionItem(
                    session.getId(),
                    session.getDeviceId(),
                    session.getPlatform(),
                    session.getAuthType(),
                    session.getDeviceName(),
                    session.getIpAddr(),
                    TimeFormat.utc(session.getCreatedAt()),
                    TimeFormat.utc(session.getUpdatedAt()),
                    session.getId().equals(me.sessionId())));
        }
        return new SessionListResponse(items.size(), items);
    }

    // ---------------- 7. 踢下线指定设备 ----------------

    @Transactional(rollbackFor = Exception.class)
    public void revokeSession(SessionRevokeRequest request) {
        LoginUser me = UserContext.get();
        long sessionId = parseSessionId(request.sessionId());

        if (sessionId == me.sessionId()) {
            throw new BizException(ErrorCode.CANNOT_REVOKE_CURRENT_SESSION);
        }
        AuthSession session = authSessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(me.userId())) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND);
        }
        if (session.isSessionRevoked()) {
            throw new BizException(ErrorCode.SESSION_ALREADY_REVOKED);
        }
        tokenService.revokeSession(sessionId);
    }

    private long parseSessionId(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SESSION_NOT_FOUND);
        }
    }

    // ---------------- 8. MVP 未实现接口（契约已冻结） ----------------

    public void bindNotImplemented() {
        throw new BizException(ErrorCode.NOT_IMPLEMENTED_IN_MVP, "bind");
    }

    public void unbindNotImplemented() {
        throw new BizException(ErrorCode.NOT_IMPLEMENTED_IN_MVP, "unbind");
    }

    public void changePhoneNotImplemented() {
        throw new BizException(ErrorCode.NOT_IMPLEMENTED_IN_MVP, "change_phone");
    }

    public void aiVerifyNotImplemented() {
        throw new BizException(ErrorCode.NOT_IMPLEMENTED_IN_MVP, "ai_verify");
    }
}
