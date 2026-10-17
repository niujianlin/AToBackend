package com.ato.account.service;

import com.ato.account.entity.AuthCredential;
import com.ato.account.entity.UserInfo;
import com.ato.account.mapper.AuthCredentialMapper;
import com.ato.account.mapper.UserInfoMapper;
import com.ato.account.util.CryptoUtil;
import com.ato.account.util.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 新用户注册（自动建号）。独立事务：并发抢占同一手机号时由唯一索引
 * uk_type_identifier_hash 兜底，冲突整体回滚，不产生孤儿用户。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final UserInfoMapper userInfoMapper;
    private final AuthCredentialMapper authCredentialMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final CryptoUtil cryptoUtil;

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public AuthCredential registerPhoneUser(String phone) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        long userId = idGenerator.nextId();
        UserInfo user = new UserInfo();
        user.setUserId(userId);
        // 待产品确认项默认值：昵称「阿拓用户{user_id 后 6 位}」，头像空串
        user.setNickname(defaultNickname(userId));
        user.setAvatar("");
        user.setGender(0);
        user.setBio("");
        user.setAccountStatus(UserInfo.STATUS_NORMAL);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userInfoMapper.insert(user);

        AuthCredential credential = new AuthCredential();
        credential.setId(idGenerator.nextId());
        credential.setUserId(userId);
        credential.setAuthType(AuthCredential.TYPE_PHONE);
        credential.setIdentifier(cryptoUtil.encryptIdentifier(phone));
        credential.setIdentifierHash(cryptoUtil.identifierHash(AuthCredential.TYPE_PHONE, phone));
        credential.setExtInfo("");
        credential.setIsInvalid(AuthCredential.VALID);
        credential.setCreatedAt(now);
        credential.setUpdatedAt(now);
        authCredentialMapper.insert(credential);

        log.info("new user registered. userId={}, authType=phone_code", userId);
        return credential;
    }

    private static String defaultNickname(long userId) {
        String idStr = String.valueOf(userId);
        String suffix = idStr.length() > 6 ? idStr.substring(idStr.length() - 6) : idStr;
        return "阿拓用户" + suffix;
    }
}
