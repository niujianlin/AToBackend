package com.ato.account.service;

import com.ato.account.common.BizException;
import com.ato.account.common.ErrorCode;
import com.ato.account.config.AtoProperties;
import com.ato.account.util.CryptoUtil;
import com.ato.account.util.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * D13 短信验证码：下发（含三层风控）与校验（错 5 次作废）。
 * MVP 不接真实短信商，mock=true 时仅打印日志；生产替换 {@link #deliver} 实现即可。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final AtoProperties properties;
    private final CryptoUtil cryptoUtil;

    /**
     * 下发验证码。返回 false 表示触发了冷却（不消耗额度）。
     */
    public void sendCode(String phone, String scene, String ip, String deviceId) {
        AtoProperties.Sms sms = properties.getSms();
        String phoneHash = phoneHash(phone);
        String cooldownKey = RedisKeys.smsCooldown(scene, phoneHash);

        Boolean first = redisTemplate.opsForValue()
                .setIfAbsent(cooldownKey, "1", Duration.ofSeconds(sms.getCooldownSeconds()));
        if (!Boolean.TRUE.equals(first)) {
            throw new BizException(ErrorCode.SMS_TOO_FREQUENT, "cooldown");
        }

        checkQuota(RedisKeys.smsDaily(scene, phoneHash), sms.getDailyLimit(), Duration.ofDays(1));
        checkQuota(RedisKeys.smsIpHourly(ip), sms.getIpHourlyLimit(), Duration.ofHours(1));
        checkQuota(RedisKeys.smsDeviceHourly(deviceId), sms.getDeviceHourlyLimit(), Duration.ofHours(1));

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String codeKey = RedisKeys.smsCode(scene, phoneHash);
        redisTemplate.opsForValue().set(codeKey, code, Duration.ofSeconds(sms.getCodeTtlSeconds()));
        redisTemplate.delete(RedisKeys.smsErrorCount(scene, phoneHash));

        try {
            deliver(phone, code, scene);
        } catch (Exception e) {
            // 发送失败必须清理验证码，否则用户收不到码却能验证通过
            redisTemplate.delete(codeKey);
            log.error("send sms failed. scene={}, phone={}", scene, maskPhone(phone), e);
            throw new BizException(ErrorCode.SMS_SEND_FAILED);
        }
    }

    /**
     * 校验并消费验证码。错误计数达上限立即作废验证码，强制重新下发。
     */
    public void verifyAndConsume(String phone, String scene, String code) {
        AtoProperties.Sms sms = properties.getSms();
        String phoneHash = phoneHash(phone);
        String codeKey = RedisKeys.smsCode(scene, phoneHash);

        String expected = redisTemplate.opsForValue().get(codeKey);
        if (expected == null) {
            throw new BizException(ErrorCode.SMS_CODE_INVALID, "expired or not sent");
        }
        if (!expected.equals(code)) {
            String errKey = RedisKeys.smsErrorCount(scene, phoneHash);
            Long errors = redisTemplate.opsForValue().increment(errKey);
            redisTemplate.expire(errKey, Duration.ofSeconds(sms.getCodeTtlSeconds()));
            if (errors != null && errors >= sms.getMaxVerifyAttempts()) {
                redisTemplate.delete(codeKey);
                log.warn("sms code invalidated after {} wrong attempts, scene={}", errors, scene);
            }
            throw new BizException(ErrorCode.SMS_CODE_INVALID, "mismatch");
        }
        // 一次性使用
        redisTemplate.delete(codeKey);
        redisTemplate.delete(RedisKeys.smsErrorCount(scene, phoneHash));
    }

    private void checkQuota(String key, long limit, Duration window) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, window);
        }
        if (count != null && count > limit) {
            throw new BizException(ErrorCode.SMS_TOO_FREQUENT, "quota exceeded");
        }
    }

    /** 生产替换为短信服务商 SDK 调用 */
    private void deliver(String phone, String code, String scene) {
        if (properties.getSms().isMock()) {
            log.info("[MOCK SMS] scene={}, phone={}, code={}", scene, maskPhone(phone), code);
            return;
        }
        throw new IllegalStateException("real sms sender is not configured yet");
    }

    private String phoneHash(String phone) {
        return CryptoUtil.sha256Hex(phone);
    }

    private String maskPhone(String phone) {
        return phone == null || phone.length() < 7 ? "***"
                : phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
