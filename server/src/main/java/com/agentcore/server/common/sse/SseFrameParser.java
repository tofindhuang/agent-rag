package com.agentcore.server.common.sse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Minimal text/event-stream parser. Heartbeat/comment blocks are returned with
 * a null event and their raw text preserved.
 */
public final class SseFrameParser {

    private final BufferedReader reader;

    public SseFrameParser(InputStream input) {
        this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    /** Returns null at EOF. */
    public SseFrame read() throws IOException {
        String id = null;
        String event = null;
        StringBuilder data = null;
        StringBuilder raw = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (raw.length() == 0) continue;
                raw.append('\n');
                return new SseFrame(id, event, data == null ? null : data.toString(), raw.toString());
            }

            raw.append(line).append('\n');
            if (line.startsWith(":")) continue;

            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);

            switch (field) {
                case "id" -> id = value;
                case "event" -> event = value;
                case "data" -> {
                    if (data == null) data = new StringBuilder();
                    if (data.length() > 0) data.append('\n');
                    data.append(value);
                }
                default -> {
                    // Ignore retry and unknown fields.
                }
            }
        }

        if (raw.length() == 0) return null;
        raw.append('\n');
        return new SseFrame(id, event, data == null ? null : data.toString(), raw.toString());
    }
}
