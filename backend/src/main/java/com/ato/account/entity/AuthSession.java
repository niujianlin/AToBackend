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

    /**
     * 注意：方法名不能写成 isRevoked()。Lombok 会为字段 revoked 生成 getRevoked()，
     * 两者会被 MyBatis 的 Reflector 解析成同名属性 revoked 但返回类型不一致
     * （Integer vs boolean），构造 MetaClass 时直接抛
     * "Illegal overloaded getter method with ambiguous type"，
     * 导致本实体所有 insert/update 的动态 SQL 构建失败。
     */
    public boolean isSessionRevoked() {
        return revoked != null && revoked == REVOKED;
    }

    /**
     * expire_at 按 UTC 存储，比较必须用 UTC，避免宿主时区偏移导致误判。
     * 命名约束同上：不可使用 isExpired()。
     */
    public boolean isSessionExpired() {
        return expireAt == null || expireAt.isBefore(LocalDateTime.now(ZoneOffset.UTC));
    }
}
