package com.ato.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * auth_session 登录会话表。id 即对外 session_id。
 * 同 platform 最多一条 revoked=0 的记录（D1）。
 */
@Data
@TableName("auth_session")
public class AuthSession {

    public static final int NOT_REVOKED = 0;
    public static final int REVOKED = 1;

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long userId;
    private Long credentialId;
    private String refreshTokenHash;
    private String platform;
    private String authType;
    private String deviceId;
    private String deviceName;
    private String ipAddr;
    private LocalDateTime expireAt;
    private Integer revoked;
    private LocalDateTime lastRefreshAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isRevoked() {
        return revoked != null && revoked == REVOKED;
    }

    /** expire_at 按 UTC 存储，比较必须用 UTC，避免宿主时区偏移导致误判 */
    public boolean isExpired() {
        return expireAt == null || expireAt.isBefore(LocalDateTime.now(ZoneOffset.UTC));
    }
}
