package com.ato.account.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全部可配置项集中管理：密钥一律来自环境变量/KMS，不入库、不入 Git。
 */
@Data
@ConfigurationProperties(prefix = "ato")
public class AtoProperties {

    private Jwt jwt = new Jwt();
    private Crypto crypto = new Crypto();
    private Snowflake snowflake = new Snowflake();
    private Sms sms = new Sms();
    private ThirdParty wechat = new ThirdParty();
    private ThirdParty apple = new ThirdParty();

    @Data
    public static class Jwt {
        private String secret;
        private String issuer = "ato-account";
        /** access_token 有效期（秒） */
        private long accessExpireSeconds = 7200;
        /** refresh_token 有效期（秒） */
        private long refreshExpireSeconds = 2592000;
        /** 刷新重放宽限窗口（秒），窗口内重复使用旧 token 视为并发重试 */
        private long refreshGraceSeconds = 30;
    }

    @Data
    public static class Crypto {
        private String aesKey;
        private String keyId = "k1";
        private String hmacKey;
    }

    @Data
    public static class Snowflake {
        private long workerId = 1;
    }

    @Data
    public static class Sms {
        private long codeTtlSeconds = 300;
        private long cooldownSeconds = 60;
        private long dailyLimit = 10;
        private long ipHourlyLimit = 20;
        private long deviceHourlyLimit = 20;
        private long maxVerifyAttempts = 5;
        /** true=仅打印日志不真实发送 */
        private boolean mock = true;
    }

    @Data
    public static class ThirdParty {
        /** v1.1 使用：微信/Apple 登录开关 */
        private boolean enabled = false;
    }
}
