package com.agentcore.server.dao;

import com.agentcore.server.domain.KbEvalRun;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Persistence access for evaluation runs and their history window (ADR-0022). */
@Mapper
public interface KbEvalRunDao extends BaseMapper<KbEvalRun> {

    int insertRun(KbEvalRun run);

    KbEvalRun findOwned(@Param("id") long id, @Param("userId") long userId);

    List<KbEvalRun> listByKb(
            @Param("userId") long userId,
            @Param("kbId") String kbId,
            @Param("limit") int limit);

    /** Keeps the newest {@code keep} runs for the KB; item rows cascade. */
    int prune(@Param("userId") long userId, @Param("kbId") String kbId, @Param("keep") int keep);
}
