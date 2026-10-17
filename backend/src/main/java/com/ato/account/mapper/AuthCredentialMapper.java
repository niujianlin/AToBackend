package com.ato.account.mapper;

import com.ato.account.entity.AuthCredential;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AuthCredentialMapper extends BaseMapper<AuthCredential> {

    /** 登录/绑定场景的等值查询，走 uk_type_identifier_hash */
    @Select("SELECT * FROM auth_credential WHERE auth_type = #{authType} AND identifier_hash = #{identifierHash} LIMIT 1")
    AuthCredential selectByIdentifierHash(@Param("authType") String authType,
                                          @Param("identifierHash") String identifierHash);

    /** 账号有效凭证列表（用于"最后一条凭证禁止解绑"判断） */
    @Select("SELECT * FROM auth_credential WHERE user_id = #{userId} AND is_invalid = 0")
    List<AuthCredential> listValidByUser(@Param("userId") Long userId);

    /** D4/D16 软删并释放 identifier：清空密文、改写 hash 让出唯一索引 */
    @Update("UPDATE auth_credential SET is_invalid = 1, identifier = '', identifier_hash = #{deadHash} "
            + "WHERE id = #{id} AND is_invalid = 0")
    int softDeleteAndRelease(@Param("id") Long id, @Param("deadHash") String deadHash);
}
