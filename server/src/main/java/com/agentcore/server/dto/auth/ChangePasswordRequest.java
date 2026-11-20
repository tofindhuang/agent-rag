package com.agentcore.server.dto.auth;

/** Verifies the current password before replacing it; both stay write-only. */
public record ChangePasswordRequest(String currentPassword, String newPassword) {}
