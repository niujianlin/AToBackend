package com.ato.account.context;

import com.ato.account.common.BizException;
import com.ato.account.common.ErrorCode;

/**
 * 当前请求登录态（由 AuthInterceptor 写入，请求结束清理）。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            throw new BizException(ErrorCode.NOT_LOGGED_IN);
        }
        return user;
    }

    public static void clear() {
        HOLDER.remove();
    }

    public record LoginUser(Long userId, Long sessionId, String platform) {
    }
}
