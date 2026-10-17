package com.ato.account.interceptor;

import com.ato.account.common.BizException;
import com.ato.account.common.ErrorCode;
import com.ato.account.context.UserContext;
import com.ato.account.entity.AuthSession;
import com.ato.account.mapper.AuthSessionMapper;
import com.ato.account.util.JwtUtil;
import com.ato.account.util.RedisKeys;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

/**
 * D10：access_token 无状态验签 + Redis 会话状态校验，实现"登出/踢设备即时失效"。
 * Redis 不可用时 fail-open 并告警（MVP 策略，避免 Redis 抖动导致全站不可用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final AuthSessionMapper authSessionMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new BizException(ErrorCode.NOT_LOGGED_IN, "missing bearer token");
        }
        Claims claims;
        try {
            claims = jwtUtil.parse(header.substring(BEARER_PREFIX.length()).trim());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.NOT_LOGGED_IN, "invalid token");
        }

        long sessionId;
        try {
            sessionId = Long.parseLong(String.valueOf(claims.get(JwtUtil.CLAIM_SESSION_ID)));
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.NOT_LOGGED_IN, "invalid sid");
        }
        String platform = String.valueOf(claims.get(JwtUtil.CLAIM_PLATFORM));

        if (isRevoked(sessionId, jwtUtil.remainingSeconds(claims))) {
            throw new BizException(ErrorCode.NOT_LOGGED_IN, "session revoked");
        }
        UserContext.set(new UserContext.LoginUser(Long.parseLong(claims.getSubject()), sessionId, platform));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    /** 先查 Redis 标记，未命中回源 DB 并回填 */
    private boolean isRevoked(long sessionId, long ttlSeconds) {
        String key = RedisKeys.sessionRevoked(sessionId);
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return "1".equals(cached);
            }
        } catch (Exception e) {
            log.warn("redis unavailable while checking session, fail-open. sid={}, err={}", sessionId, e.getMessage());
            return false;
        }

        AuthSession session = authSessionMapper.selectById(sessionId);
        boolean revoked = session == null || session.isRevoked() || session.isExpired();
        try {
            if (ttlSeconds > 0) {
                redisTemplate.opsForValue().set(key, revoked ? "1" : "0", Duration.ofSeconds(ttlSeconds));
            }
        } catch (Exception e) {
            log.warn("redis unavailable while caching session state. sid={}", sessionId);
        }
        return revoked;
    }
}
