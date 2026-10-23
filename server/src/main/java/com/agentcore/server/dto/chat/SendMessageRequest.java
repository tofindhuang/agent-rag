package com.agentcore.server.dto.chat;

/**
 * @param text user input
 * @param turnId optional client-supplied turn id; repeating it retries the turn
 *               instead of creating a new one
 */
public record SendMessageRequest(String text, String turnId) {}
