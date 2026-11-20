package com.agentcore.server.dao;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Session → selected memory collections (FR-5 extension). */
@Mapper
public interface SessionProfileCollectionDao {

    List<String> listSelected(@Param("sessionId") String sessionId);

    int deleteBySession(@Param("sessionId") String sessionId);

    int insert(@Param("sessionId") String sessionId, @Param("collectionId") String collectionId);
}
