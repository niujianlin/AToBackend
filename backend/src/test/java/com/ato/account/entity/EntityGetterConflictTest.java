package com.ato.account.entity;

import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 回归测试：实体上不能存在同名但返回类型不一致的 getter。
 *
 * <p>背景：AuthSession 曾同时存在 Lombok 为 {@code Integer revoked} 生成的
 * {@code getRevoked()} 与手写的 {@code boolean isRevoked()}，两者都指向属性
 * {@code revoked}。MyBatis 的 Reflector 在 resolveGetterConflicts 阶段会抛
 * "Illegal overloaded getter method with ambiguous type"，导致该实体所有
 * insert/update 的动态 SQL（{@code <if test="revoked != null">}）构建失败，
 * 表现就是登录接口 500。
 *
 * <p>Reflector 在构造时即解析全部 getter/setter，因此用它做守卫。
 */
class EntityGetterConflictTest {

    private final DefaultReflectorFactory reflectorFactory = new DefaultReflectorFactory();

    @Test
    @DisplayName("实体解析 Reflector 不得出现 getter 歧义")
    void entityReflectorShouldBeBuildable() {
        assertDoesNotThrow(() -> reflectorFactory.findForClass(AuthSession.class), "AuthSession 存在歧义 getter");
        assertDoesNotThrow(() -> reflectorFactory.findForClass(AuthCredential.class), "AuthCredential 存在歧义 getter");
        assertDoesNotThrow(() -> reflectorFactory.findForClass(UserInfo.class), "UserInfo 存在歧义 getter");
    }
}
