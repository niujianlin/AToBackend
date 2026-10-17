package com.ato.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * auth_credential 登录凭证表。MVP 只写 phone_code 类型，
 * wechat / apple 的列与唯一索引保持不变，v1.1 无需改表。
 */
@Data
@TableName("auth_credential")
public class AuthCredential {

    public static final String TYPE_WECHAT = "wechat";
    public static final String TYPE_APPLE = "apple";
    public static final String TYPE_PHONE = "phone_code";

    public static final int VALID = 0;
    public static final int INVALID = 1;

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long userId;
    private String authType;
    private String identifier;
    private String identifierHash;
    private String extInfo;
    private Integer isInvalid;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
