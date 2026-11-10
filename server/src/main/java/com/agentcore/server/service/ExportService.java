package com.agentcore.server.service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Personal data export (ADR-0034): streams one ZIP with a JSON file per
 * domain. Everything is queried by {@code user_id}; credentials, secrets,
 * other users and binary originals never enter the package.
 */
public interface ExportService {

    /**
     * Rejects the request before any stream bytes are produced. Checked
     * before the daily counter so an oversized account does not burn one of
     * its three attempts.
     */
    void requireWithinBudget(long userId);

    /** Counts today's exports; throws when the user already hit the daily cap. */
    void requireWithinDailyLimit(long userId);

    /** Pessimistic package size estimate used by the budget check. */
    long estimateBytes(long userId);

    /**
     * Writes the package; callers own the HTTP response.
     *
     * @return the entry count recorded in the manifest (spec: audit on success)
     */
    long write(long userId, OutputStream out) throws IOException;

    /** Test seam: the ZIP writes eight domain files plus the manifest. */
    static List<String> entryNames() {
        return List.of(
                "manifest.json", "account.json", "sessions.json", "messages.json",
                "compactions.json", "profile.json", "knowledge-bases.json",
                "usage.json", "audit.json");
    }
}
