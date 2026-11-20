package com.agentcore.server.dto.chat;

/** Result of a branch: the text to prefill plus how many rows were hidden. */
public record BranchResponse(String editorText, int hiddenCount) {}
