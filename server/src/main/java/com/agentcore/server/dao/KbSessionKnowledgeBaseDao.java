package com.agentcore.server.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Session to KB association; one row per session in the current slice. */
@Mapper
public interface KbSessionKnowledgeBaseDao {

    String findKbId(@Param("sessionId") String sessionId);

    int insert(@Param("sessionId") String sessionId, @Param("kbId") String kbId);

    int deleteBySession(@Param("sessionId") String sessionId);

    long countByKb(@Param("kbId") String kbId);
}
