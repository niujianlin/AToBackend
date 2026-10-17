package com.ato.account.mapper;

import com.ato.account.entity.AuthSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AuthSessionMapper extends BaseMapper<AuthSession> {

    /** D2：刷新时行锁，防止并发刷新把同一 token 判成重放 */
    @Select("SELECT * FROM auth_session WHERE refresh_token_hash = #{hash} FOR UPDATE")
    AuthSession selectByRefreshHashForUpdate(@Param("hash") String hash);

    @Select("SELECT * FROM auth_session WHERE refresh_token_hash = #{hash} LIMIT 1")
    AuthSession selectByRefreshHash(@Param("hash") String hash);

    /** D1：同端互踢（登录成功后执行，与插入新会话同事务） */
    @Update("UPDATE auth_session SET revoked = 1 WHERE user_id = #{userId} AND platform = #{platform} AND revoked = 0")
    int revokeByUserAndPlatform(@Param("userId") Long userId, @Param("platform") String platform);

    /** 注销：全端下线 */
    @Update("UPDATE auth_session SET revoked = 1 WHERE user_id = #{userId} AND revoked = 0")
    int revokeAllByUser(@Param("userId") Long userId);

    /** 换绑：除当前会话外全部下线（v1.1 使用） */
    @Update("UPDATE auth_session SET revoked = 1 WHERE user_id = #{userId} AND id <> #{keepSessionId} AND revoked = 0")
    int revokeAllByUserExcept(@Param("userId") Long userId, @Param("keepSessionId") Long keepSessionId);

    /** 设备列表：revoked=0 且未过期。now 由应用传 UTC，避免依赖 DB 时区 */
    @Select("SELECT * FROM auth_session WHERE user_id = #{userId} AND revoked = 0 AND expire_at > #{now} "
            + "ORDER BY updated_at DESC")
    List<AuthSession> listActiveByUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
