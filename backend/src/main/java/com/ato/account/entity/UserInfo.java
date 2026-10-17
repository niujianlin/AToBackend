package com.ato.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * user_info 用户信息表。account_status 是账号可用性的唯一判据（D20）。
 */
@Data
@TableName("user_info")
public class UserInfo {

    public static final int STATUS_BANNED = 0;
    public static final int STATUS_NORMAL = 1;
    public static final int STATUS_ABNORMAL = 2;
    public static final int STATUS_DEACTIVATED = 3;

    @TableId(type = IdType.INPUT)
    private Long userId;
    private String nickname;
    private String avatar;
    private Integer gender;
    private String bio;
    private Integer accountStatus;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
