package com.ato.account.util;

import com.ato.account.config.AtoProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * D11 敏感字段处理：
 * - identifier：AES-256-GCM 信封加密，密文形如 keyId:iv:ct:tag（base64）
 * - identifier_hash：HMAC-SHA256(auth_type + ":" + 明文)，用于等值查询与唯一约束（加盐防彩虹表）
 * - refresh_token：SHA-256 十六进制入库
 */
@Component
public class CryptoUtil {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] aesKey;
    private final byte[] hmacKey;
    private final String keyId;

    public CryptoUtil(AtoProperties properties) {
        AtoProperties.Crypto crypto = properties.getCrypto();
        this.aesKey = sha256Bytes(crypto.getAesKey());
        this.hmacKey = sha256Bytes(crypto.getHmacKey());
        this.keyId = crypto.getKeyId();
    }

    /** 加密 identifier，返回 keyId:iv:ct:tag */
    public String encryptIdentifier(String plain) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] out = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            // GCM 输出末尾 16 字节为 tag
            int ctLen = out.length - GCM_TAG_BITS / 8;
            byte[] ct = new byte[ctLen];
            byte[] tag = new byte[GCM_TAG_BITS / 8];
            System.arraycopy(out, 0, ct, 0, ctLen);
            System.arraycopy(out, ctLen, tag, 0, tag.length);
            Base64.Encoder enc = Base64.getEncoder();
            return keyId + ":" + enc.encodeToString(iv) + ":" + enc.encodeToString(ct) + ":" + enc.encodeToString(tag);
        } catch (Exception e) {
            throw new IllegalStateException("encrypt identifier failed", e);
        }
    }

    public String decryptIdentifier(String cipherText) {
        String[] parts = cipherText.split(":");
        if (parts.length != 4) {
            throw new IllegalArgumentException("illegal cipher text format");
        }
        try {
            Base64.Decoder dec = Base64.getDecoder();
            byte[] iv = dec.decode(parts[1]);
            byte[] ct = dec.decode(parts[2]);
            byte[] tag = dec.decode(parts[3]);
            byte[] joined = new byte[ct.length + tag.length];
            System.arraycopy(ct, 0, joined, 0, ct.length);
            System.arraycopy(tag, 0, joined, ct.length, tag.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(joined), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("decrypt identifier failed", e);
        }
    }

    /** identifier 查询索引值：HMAC-SHA256(auth_type + ":" + identifier) */
    public String identifierHash(String authType, String identifier) {
        return hmacSha256Hex(hmacKey, authType + ":" + identifier);
    }

    /** 解绑/注销占位值，用于让出唯一索引 */
    public String deadIdentifierHash(long credentialId) {
        return "dead_" + credentialId;
    }

    public static String sha256Hex(String raw) {
        return toHex(digest("SHA-256", raw.getBytes(StandardCharsets.UTF_8)));
    }

    public static String hmacSha256Hex(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return toHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("hmac failed", e);
        }
    }

    private static byte[] sha256Bytes(String raw) {
        return digest("SHA-256", raw.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("digest failed", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
