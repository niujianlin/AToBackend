package com.ato.account.common;

import lombok.Getter;

/**
 * 业务异常：由全局异常处理器转换为 HTTP 200 + 业务错误码。
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMsg());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String detail) {
        super(errorCode.getMsg() + (detail == null ? "" : ": " + detail));
        this.errorCode = errorCode;
    }

    public static BizException of(ErrorCode errorCode) {
        return new BizException(errorCode);
    }
}
