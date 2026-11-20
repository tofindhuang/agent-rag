package com.agentcore.server.dao;

import com.agentcore.server.domain.KbKnowledgeBase;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Persistence access for user-owned knowledge bases. */
@Mapper
public interface KbKnowledgeBaseDao extends BaseMapper<KbKnowledgeBase> {

    KbKnowledgeBase findOwned(@Param("id") String id, @Param("userId") long userId);

    KbKnowledgeBase findDefault(@Param("userId") long userId);

    List<KbKnowledgeBase> listByUser(@Param("userId") long userId);

    long countByUser(@Param("userId") long userId);
}
