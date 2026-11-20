package com.agentcore.server.dto.kb;

import java.util.List;

/** Cursor page of passages; nextSeq is null when no more rows exist. */
public record KbPassagePageResponse(List<KbPassageView> items, Integer nextSeq) {}
