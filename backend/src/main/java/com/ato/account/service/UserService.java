package com.ato.account.service;

import com.ato.account.common.BizException;
import com.ato.account.common.ErrorCode;
import com.ato.account.context.UserContext;
import com.ato.account.dto.AuthDtos.ProfileUpdateRequest;
import com.ato.account.dto.AuthDtos.UserProfileResponse;
import com.ato.account.entity.UserInfo;
import com.ato.account.mapper.UserInfoMapper;
import com.ato.account.util.TimeFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserInfoMapper userInfoMapper;

    public UserProfileResponse me() {
        Long userId = UserContext.get().userId();
        UserInfo user = userInfoMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_LOGGED_IN, "user not found");
        }
        return new UserProfileResponse(user.getUserId(), user.getNickname(), user.getAvatar(),
                user.getGender(), user.getBio(), user.getAccountStatus(), TimeFormat.utc(user.getCreatedAt()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateProfile(ProfileUpdateRequest req) {
        if (req.gender() != null && (req.gender() < 0 || req.gender() > 2)) {
            throw new BizException(ErrorCode.PROFILE_FIELD_INVALID, "gender");
        }
        if (req.nickname() == null && req.avatar() == null && req.gender() == null && req.bio() == null) {
            throw new BizException(ErrorCode.PROFILE_FIELD_INVALID, "empty payload");
        }

        Long userId = UserContext.get().userId();
        UserInfo user = userInfoMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_LOGGED_IN, "user not found");
        }
        if (req.nickname() != null) {
            String nickname = req.nickname().trim();
            if (nickname.isEmpty()) {
                throw new BizException(ErrorCode.PROFILE_FIELD_INVALID, "nickname");
            }
            user.setNickname(nickname);
        }
        if (req.avatar() != null) {
            user.setAvatar(req.avatar());
        }
        if (req.gender() != null) {
            user.setGender(req.gender());
        }
        if (req.bio() != null) {
            user.setBio(req.bio());
        }
        user.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        userInfoMapper.updateById(user);
    }
}
