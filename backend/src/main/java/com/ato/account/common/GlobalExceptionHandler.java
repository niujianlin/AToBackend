package com.ato.account.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ApiResult<Void> handleBiz(BizException e) {
        log.warn("biz error: code={}, msg={}", e.getErrorCode().getCode(), e.getMessage());
        return ApiResult.fail(e.getErrorCode().getCode(), e.getMessage());
    }

    /**
     * D14：参数错误统一 40003，不暴露内部字段细节。
     */
    @ExceptionHandler({MethodArgumentNotValidException.class,
            MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    public ApiResult<Void> handleParam(Exception e) {
        String detail = "";
        if (e instanceof MethodArgumentNotValidException manv) {
            detail = manv.getBindingResult().getFieldErrors().stream()
                    .map(FieldError::getField)
                    .collect(Collectors.joining(","));
        }
        log.warn("param error: {}", e.getMessage());
        return ApiResult.fail(ErrorCode.MISSING_PARAMETER.getCode(),
                ErrorCode.MISSING_PARAMETER.getMsg() + (detail.isEmpty() ? "" : ": " + detail));
    }

    /**
     * 唯一索引冲突：仅在调用方未捕获时兜底，避免把 SQL 细节返回前端。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ApiResult<Void> handleDuplicate(DuplicateKeyException e) {
        log.warn("duplicate key: {}", e.getMessage());
        return ApiResult.fail(ErrorCode.CREDENTIAL_INVALID.getCode(), ErrorCode.CREDENTIAL_INVALID.getMsg());
    }

    @ExceptionHandler(Exception.class)
    public ApiResult<Void> handleOther(Exception e) {
        log.error("unexpected error", e);
        return ApiResult.fail(ErrorCode.INTERNAL_ERROR);
    }
}
