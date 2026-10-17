package com.ato.account.util;

/**
 * Redis key 统一命名，避免散落各处产生歧义。
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** 验证码：sms:{scene}:{phone_hash} -> 6 位数字 */
    public static String smsCode(String scene, String phoneHash) {
        return "sms:" + scene + ":" + phoneHash;
    }

    /** 验证码错误次数 */
    public static String smsErrorCount(String scene, String phoneHash) {
        return "sms:err:" + scene + ":" + phoneHash;
    }

    /** 手机号+场景冷却 */
    public static String smsCooldown(String scene, String phoneHash) {
        return "sms:cd:" + scene + ":" + phoneHash;
    }

    /** 手机号+场景 每日额度 */
    public static String smsDaily(String scene, String phoneHash) {
        return "sms:day:" + scene + ":" + phoneHash;
    }

    /** IP 小时额度 */
    public static String smsIpHourly(String ip) {
        return "sms:ip:" + ip;
    }

    /** 设备小时额度 */
    public static String smsDeviceHourly(String deviceId) {
        return "sms:dev:" + deviceId;
    }

    /** 会话吊销标记：sess:{sid}:revoked -> 1（TTL=access 剩余有效期） */
    public static String sessionRevoked(long sessionId) {
        return "sess:" + sessionId + ":revoked";
    }

    /** 会话平台标记：sess:{sid}:plat -> platform */
    public static String sessionPlatform(long sessionId) {
        return "sess:" + sessionId + ":plat";
    }

    /** 刷新宽限期幂等缓存：rt:grace:{oldHash} -> 新 token 对 JSON */
    public static String refreshGrace(String oldHash) {
        return "rt:grace:" + oldHash;
    }

    /** 已使用过的旧 refresh_token（重放检测）：rt:used:{oldHash} */
    public static String refreshUsed(String oldHash) {
        return "rt:used:" + oldHash;
    }
}
