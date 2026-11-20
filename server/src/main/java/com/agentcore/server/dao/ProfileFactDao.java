package com.agentcore.server.dao;

import com.agentcore.server.domain.ProfileFact;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Persistence access for long-term profile facts (FR-5). */
@Mapper
public interface ProfileFactDao {

    ProfileFact findActive(
            @Param("userId") long userId,
            @Param("category") String category,
            @Param("factKey") String factKey);

    List<ProfileFact> listByUser(
            @Param("userId") long userId,
            @Param("status") String status,
            @Param("collectionId") String collectionId);

    ProfileFact findOwned(@Param("id") String id, @Param("userId") long userId);

    int insert(ProfileFact fact);

    int supersede(
            @Param("id") String id,
            @Param("userId") long userId,
            @Param("updatedAt") LocalDateTime updatedAt);

    int updateText(
            @Param("id") String id,
            @Param("userId") long userId,
            @Param("category") String category,
            @Param("factTitle") String factTitle,
            @Param("factText") String factText,
            @Param("updatedAt") LocalDateTime updatedAt);

    int markDeleted(
            @Param("id") String id,
            @Param("userId") long userId,
            @Param("updatedAt") LocalDateTime updatedAt);

    List<ProfileFact> listByCollections(
            @Param("userId") long userId,
            @Param("collectionIds") java.util.List<String> collectionIds,
            @Param("limit") int limit);

    int moveCollection(
            @Param("id") String id,
            @Param("userId") long userId,
            @Param("collectionId") String collectionId,
            @Param("updatedAt") LocalDateTime updatedAt);

    int moveCollections(
            @Param("ids") java.util.List<String> ids,
            @Param("userId") long userId,
            @Param("collectionId") String collectionId,
            @Param("updatedAt") LocalDateTime updatedAt);

    int moveAll(
            @Param("userId") long userId,
            @Param("fromCollectionId") String fromCollectionId,
            @Param("toCollectionId") String toCollectionId,
            @Param("updatedAt") LocalDateTime updatedAt);
}
