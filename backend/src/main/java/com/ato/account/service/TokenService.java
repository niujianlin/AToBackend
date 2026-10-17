package com.ato.account.service;

import com.ato.account.common.BizException;
import com.ato.account.common.ErrorCode;
import com.ato.account.config.AtoProperties;
import com.ato.account.dto.AuthDtos.TokenPair;
import com.ato.account.entity.AuthSession;
import com.ato.account.entity.UserInfo;
import com.ato.account.mapper.AuthSessionMapper;
import com.ato.account.mapper.UserInfoMapper;
import com.ato.account.util.CryptoUtil;
import com.ato.account.util.JwtUtil;
import com.ato.account.util.RedisKeys;
import com.ato.account.util.SnowflakeIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 会话与令牌：登录建会话（含同端互踢）、刷新（含宽限幂等与重放检测）、吊销与缓存同步。
 *
 * 关键约束：任何把 auth_session.revoked 置 1 的路径，都必须同步写 Redis 负缓存，
 * 否则拦截器可能命中过期的 "0" 缓存而放行已吊销会话。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int REFRESH_TOKEN_BYTES = 32;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final AuthSessionMapper authSessionMapper;
    private final UserInfoMapper userInfoMapper;
    private final StringRedisTemplate redisTemplate;
    private final JwtUtil jwtUtil;
    private final SnowflakeIdGenerator idGenerator;
    private final AtoProperties properties;
    private final ObjectMapper objectMapper;

    public record LoginContext(String platform, String deviceId, String deviceName, String ip) {
    }

    /**
     * D1：先踢同端旧会话，再建新会话，两者同事务。
     */
    @Transactional(rollbackFor = Exception.class)
    public TokenPair loginAndIssueSession(Long userId, Long credentialId, String authType, LoginContext ctx) {
        List<AuthSession> actives = authSessionMapper.listActiveByUser(userId, nowUtc());
        int kicked = authSessionMapper.revokeByUserAndPlatform(userId, ctx.platform());
        if (kicked > 0) {
            actives.stream()
                    .filter(s -> ctx.platform().equals(s.getPlatform()))
                    .forEach(s -> cacheRevoked(s.getId()));
            log.info("kicked {} session(s) on platform {} for user {}", kicked, ctx.platform(), userId);
        }
        return insertSession(userId, credentialId, authType, ctx);
    }

    private TokenPair insertSession(Long userId, Long credentialId, String authType, LoginContext ctx) {
        long sessionId = idGenerator.nextId();
        String refreshToken = randomToken();
        LocalDateTime now = nowUtc();
        AtoProperties.Jwt jwt = properties.getJwt();

        AuthSession session = new AuthSession();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setCredentialId(credentialId);
        session.setRefreshTokenHash(CryptoUtil.sha256Hex(refreshToken));
        session.setPlatform(ctx.platform());
        session.setAuthType(authType);
        session.setDeviceId(nullToEmpty(ctx.deviceId()));
        session.setDeviceName(nullToEmpty(ctx.deviceName()));
        session.setIpAddr(nullToEmpty(ctx.ip()));
        session.setExpireAt(now.plusSeconds(jwt.getRefreshExpireSeconds()));
        session.setRevoked(AuthSession.NOT_REVOKED);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        authSessionMapper.insert(session);

        String accessToken = jwtUtil.generateAccessToken(userId, sessionId, ctx.platform());
        cacheActive(sessionId, ctx.platform());
        return new TokenPair(accessToken, refreshToken, (int) jwt.getAccessExpireSeconds(), (int) jwt.getRefreshExpireSeconds());
    }

    /**
     * D2：原地刷新 + Redis 重放检测 + 30s 宽限幂等。
     */
    @Transactional(rollbackFor = Exception.class)
    public TokenPair refresh(String refreshToken) {
        String oldHash = CryptoUtil.sha256Hex(refreshToken);

        TokenPair graceHit = readGrace(oldHash);
        if (graceHit != null) {
            log.info("refresh hit grace window, return cached token pair");
            return graceHit;
        }

        AuthSession session = authSessionMapper.selectByRefreshHashForUpdate(oldHash);
        if (session == null) {
            // 旧 token 已不在库中：只有"确实用过"才算重放，否则是伪造
            handleReplay(oldHash);
            throw new BizException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        if (session.isRevoked() || session.isExpired()) {
            throw new BizException(ErrorCode.REFRESH_TOKEN_INVALID, "session revoked or expired");
        }
        checkAccountStatus(session.getUserId(), true);

        AtoProperties.Jwt jwt = properties.getJwt();
        String newRefresh = randomToken();
        LocalDateTime now = nowUtc();
        session.setRefreshTokenHash(CryptoUtil.sha256Hex(newRefresh));
        session.setExpireAt(now.plusSeconds(jwt.getRefreshExpireSeconds()));
        session.setLastRefreshAt(now);
        session.setUpdatedAt(now);
        authSessionMapper.updateById(session);

        String accessToken = jwtUtil.generateAccessToken(session.getUserId(), session.getId(), session.getPlatform());
        TokenPair pair = new TokenPair(accessToken, newRefresh, (int) jwt.getAccessExpireSeconds(), (int) jwt.getRefreshExpireSeconds());

        writeGrace(oldHash, pair, session.getUserId());
        cacheActive(session.getId(), session.getPlatform());
        return pair;
    }

    /** 单会话吊销（登出 / 踢设备） */
    public void revokeSession(Long sessionId) {
        AuthSession session = authSessionMapper.selectById(sessionId);
        if (session == null || session.isRevoked()) {
            return;
        }
        session.setRevoked(AuthSession.REVOKED);
        session.setUpdatedAt(nowUtc());
        authSessionMapper.updateById(session);
        cacheRevoked(sessionId);
    }

    /** 全端吊销（注销 / 重放泄露），并同步 Redis 负缓存 */
    public int revokeAllSessions(Long userId) {
        List<AuthSession> actives = authSessionMapper.listActiveByUser(userId, nowUtc());
        int affected = authSessionMapper.revokeAllByUser(userId);
        actives.forEach(s -> cacheRevoked(s.getId()));
        return affected;
    }

    /**
     * D14：凭证通过后再判账号状态，避免用错误码枚举账号。
     * @param forRefresh true 使用 40103/40104/40105 刷新域错误码
     */
    public void checkAccountStatus(Long userId, boolean forRefresh) {
        UserInfo user = userInfoMapper.selectById(userId);
        if (user == null) {
            throw new BizException(forRefresh ? ErrorCode.REFRESH_TOKEN_INVALID : ErrorCode.ACCOUNT_DEACTIVATED);
        }
        int status = user.getAccountStatus() == null ? UserInfo.STATUS_ABNORMAL : user.getAccountStatus();
        switch (status) {
            case UserInfo.STATUS_NORMAL -> {
            }
            case UserInfo.STATUS_BANNED -> throw new BizException(forRefresh
                    ? ErrorCode.RT_ACCOUNT_BANNED : ErrorCode.ACCOUNT_BANNED);
            case UserInfo.STATUS_DEACTIVATED -> throw new BizException(forRefresh
                    ? ErrorCode.RT_ACCOUNT_DEACTIVATED : ErrorCode.ACCOUNT_DEACTIVATED);
            default -> throw new BizException(forRefresh
                    ? ErrorCode.RT_ACCOUNT_ABNORMAL : ErrorCode.ACCOUNT_ABNORMAL);
        }
    }

    // ---------------- Redis 辅助 ----------------

    private void handleReplay(String oldHash) {
        String usedKey = RedisKeys.refreshUsed(oldHash);
        String userIdStr = redisTemplate.opsForValue().get(usedKey);
        if (userIdStr == null) {
            return;
        }
        try {
            long userId = Long.parseLong(userIdStr);
            int affected = revokeAllSessions(userId);
            log.warn("refresh token replay detected! all {} session(s) of user {} revoked", affected, userId);
        } catch (NumberFormatException e) {
            log.warn("illegal used-refresh-token payload: {}", userIdStr);
        }
    }

    private TokenPair readGrace(String oldHash) {
        String json = redisTemplate.opsForValue().get(RedisKeys.refreshGrace(oldHash));
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, TokenPair.class);
        } catch (Exception e) {
            log.warn("cannot deserialize grace token pair, ignore", e);
            return null;
        }
    }

    private void writeGrace(String oldHash, TokenPair pair, Long userId) {
        try {
            redisTemplate.opsForValue().set(RedisKeys.refreshGrace(oldHash),
                    objectMapper.writeValueAsString(pair),
                    Duration.ofSeconds(properties.getJwt().getRefreshGraceSeconds()));
            redisTemplate.opsForValue().set(RedisKeys.refreshUsed(oldHash),
                    String.valueOf(userId),
                    Duration.ofSeconds(properties.getJwt().getRefreshExpireSeconds()));
        } catch (Exception e) {
            log.error("write refresh grace cache failed, replay detection degraded", e);
        }
    }

    private void cacheActive(long sessionId, String platform) {
        try {
            long ttl = properties.getJwt().getAccessExpireSeconds();
            redisTemplate.opsForValue().set(RedisKeys.sessionRevoked(sessionId), "0", Duration.ofSeconds(ttl));
            redisTemplate.opsForValue().set(RedisKeys.sessionPlatform(sessionId), platform, Duration.ofSeconds(ttl));
        } catch (Exception e) {
            log.warn("cache session state failed. sid={}", sessionId);
        }
    }

    private void cacheRevoked(long sessionId) {
        try {
            redisTemplate.opsForValue().set(RedisKeys.sessionRevoked(sessionId), "1",
                    Duration.ofSeconds(properties.getJwt().getAccessExpireSeconds()));
        } catch (Exception e) {
            log.warn("cache revoked state failed. sid={}", sessionId);
        }
    }

    // ---------------- 杂项 ----------------

    private static String randomToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
