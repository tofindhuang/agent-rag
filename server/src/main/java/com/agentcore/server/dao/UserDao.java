package com.agentcore.server.dao;

import com.agentcore.server.domain.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Persistence access for {@link User}. */
@Mapper
public interface UserDao extends BaseMapper<User> {}
