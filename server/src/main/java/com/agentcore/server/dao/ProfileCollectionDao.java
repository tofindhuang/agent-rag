package com.agentcore.server.dao;

import com.agentcore.server.domain.ProfileCollection;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Persistence access for memory collections (FR-5 extension). */
@Mapper
public interface ProfileCollectionDao {

    ProfileCollection findSystem(@Param("userId") long userId, @Param("systemKey") String systemKey);

    ProfileCollection findOwned(@Param("id") String id, @Param("userId") long userId);

    List<ProfileCollection> listByUser(@Param("userId") long userId);

    int insert(ProfileCollection collection);

    int rename(
            @Param("id") String id,
            @Param("userId") long userId,
            @Param("name") String name,
            @Param("updatedAt") LocalDateTime updatedAt);

    int delete(@Param("id") String id, @Param("userId") long userId);

    int countFacts(@Param("collectionId") String collectionId, @Param("userId") long userId);
}
