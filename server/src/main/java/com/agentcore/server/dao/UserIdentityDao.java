package com.agentcore.server.dao;

import com.agentcore.server.domain.UserIdentity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Persistence access for bound OIDC identities (ADR-0027). */
@Mapper
public interface UserIdentityDao extends BaseMapper<UserIdentity> {

    @Select("SELECT * FROM user_identities WHERE issuer = #{issuer} AND subject = #{subject}")
    UserIdentity findByIssuerAndSubject(@Param("issuer") String issuer, @Param("subject") String subject);

    @Select("SELECT * FROM user_identities WHERE user_id = #{userId} ORDER BY id")
    List<UserIdentity> listByUserId(@Param("userId") long userId);

    @Select("SELECT * FROM user_identities WHERE id = #{id} AND user_id = #{userId}")
    UserIdentity findByIdAndUserId(@Param("id") long id, @Param("userId") long userId);

    @Update("UPDATE user_identities SET last_login_at = #{lastLoginAt} WHERE id = #{id}")
    int updateLastLoginAt(@Param("id") long id, @Param("lastLoginAt") LocalDateTime lastLoginAt);

    @Delete("DELETE FROM user_identities WHERE id = #{id} AND user_id = #{userId}")
    int deleteByIdAndUserId(@Param("id") long id, @Param("userId") long userId);
}
