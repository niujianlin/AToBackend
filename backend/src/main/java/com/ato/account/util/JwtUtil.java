package com.ato.account.util;

import com.ato.account.config.AtoProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.UUID;

/**
 * D10：access_token 使用 HS256 JWT（无状态校验 + Redis 即时失效）。
 * claims：sub=user_id、sid=session_id、plt=platform、jti、iss、iat、exp。
 */
@Component
public class JwtUtil {

    public static final String CLAIM_SESSION_ID = "sid";
    public static final String CLAIM_PLATFORM = "plt";

    private final SecretKey secretKey;
    private final AtoProperties.Jwt jwt;

    public JwtUtil(AtoProperties properties) {
        this.jwt = properties.getJwt();
        this.secretKey = Keys.hmacShaKeyFor(deriveKey(jwt.getSecret()));
    }

    public String generateAccessToken(long userId, long sessionId, String platform) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .issuer(jwt.getIssuer())
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_SESSION_ID, String.valueOf(sessionId))
                .claim(CLAIM_PLATFORM, platform)
                .issuedAt(new Date(now))
                .expiration(new Date(now + jwt.getAccessExpireSeconds() * 1000L))
                .signWith(secretKey)
                .compact();
    }

    /** 解析并验签，失败抛 IllegalArgumentException（由调用方转为 40100）。 */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid jwt: " + e.getMessage(), e);
        }
    }

    /** 剩余有效期（秒），已过期返回 0 */
    public long remainingSeconds(Claims claims) {
        long remain = (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000L;
        return Math.max(remain, 0L);
    }

    /** HS256 要求密钥不短于 32 字节；配置过短时派生到 32 字节，避免启动失败。 */
    private static byte[] deriveKey(String secret) {
        byte[] raw = (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8);
        if (raw.length >= 32) {
            return raw;
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw);
        } catch (Exception e) {
            throw new IllegalStateException("cannot derive jwt key", e);
        }
    }
}
