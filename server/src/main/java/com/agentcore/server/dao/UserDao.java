package com.agentcore.server.dao;

import com.agentcore.server.domain.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** Persistence access for {@link User}. */
@Mapper
public interface UserDao extends BaseMapper<User> {

    /** Bootstrap promotion; idempotent and never demotes. */
    @Update("UPDATE users SET role = 'admin' WHERE email = #{email} AND role <> 'admin'")
    int promoteToAdmin(@Param("email") String email);
}
