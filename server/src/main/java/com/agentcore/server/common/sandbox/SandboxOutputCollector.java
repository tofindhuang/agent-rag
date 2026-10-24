package com.agentcore.server.common.sandbox;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits container output into contract-sized chunks (≤ chunkMaxBytes of UTF-8,
 * on character boundaries), enforces the combined stdout+stderr cap, and keeps
 * the capped text for the audit row. After the cap it stops forwarding but the
 * caller keeps draining Docker so the container never blocks on a full pipe.
 */
public final class SandboxOutputCollector {

    private final SandboxEventSink sink;
    private final int chunkMaxBytes;
    private final int totalMaxBytes;
    private final Utf8Decoder stdoutDecoder = new Utf8Decoder();
    private final Utf8Decoder stderrDecoder = new Utf8Decoder();
    private final StringBuilder stdout = new StringBuilder();
    private final StringBuilder stderr = new StringBuilder();

    private int emittedBytes;
    private boolean truncated;

    public SandboxOutputCollector(SandboxEventSink sink, int chunkMaxBytes, int totalMaxBytes) {
        this.sink = sink;
        this.chunkMaxBytes = chunkMaxBytes;
        this.totalMaxBytes = totalMaxBytes;
    }

    public synchronized void appendStdout(byte[] payload) {
        append(false, stdoutDecoder.decode(payload));
    }

    public synchronized void appendStderr(byte[] payload) {
        append(true, stderrDecoder.decode(payload));
    }

    /** Runner-generated note (e.g. OOM), counted against the same cap. */
    public synchronized void appendStderrText(String text) {
        append(true, text);
    }

    public synchronized String stdout() {
        return stdout.toString();
    }

    public synchronized String stderr() {
        return stderr.toString();
    }

    public synchronized boolean truncated() {
        return truncated;
    }

    private void append(boolean error, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (emittedBytes >= totalMaxBytes) {
            // The cap was reached exactly at a chunk boundary; later output is dropped.
            truncated = true;
            return;
        }
        List<String> chunks = splitByUtf8Bytes(text, chunkMaxBytes);
        for (String chunk : chunks) {
            int bytes = utf8Bytes(chunk);
            if (emittedBytes + bytes > totalMaxBytes) {
                String prefix = utf8Prefix(chunk, totalMaxBytes - emittedBytes);
                if (!prefix.isEmpty()) {
                    emit(error, prefix);
                }
                truncated = true;
                return;
            }
            emit(error, chunk);
        }
    }

    private void emit(boolean error, String chunk) {
        emittedBytes += utf8Bytes(chunk);
        if (error) {
            stderr.append(chunk);
            sink.stderr(chunk);
        } else {
            stdout.append(chunk);
            sink.stdout(chunk);
        }
    }

    private static List<String> splitByUtf8Bytes(String text, int maxBytes) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentBytes = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int bytes = utf8Bytes(codePoint);
            if (currentBytes + bytes > maxBytes && current.length() > 0) {
                chunks.add(current.toString());
                current.setLength(0);
                currentBytes = 0;
            }
            current.appendCodePoint(codePoint);
            currentBytes += bytes;
            i += Character.charCount(codePoint);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private static String utf8Prefix(String text, int maxBytes) {
        if (maxBytes <= 0) {
            return "";
        }
        StringBuilder prefix = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int length = utf8Bytes(codePoint);
            if (bytes + length > maxBytes) {
                break;
            }
            prefix.appendCodePoint(codePoint);
            bytes += length;
            i += Character.charCount(codePoint);
        }
        return prefix.toString();
    }

    static int utf8Bytes(String text) {
        int bytes = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            bytes += utf8Bytes(codePoint);
            i += Character.charCount(codePoint);
        }
        return bytes;
    }

    private static int utf8Bytes(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
    }

    /** Incremental UTF-8 decoder: keeps a trailing partial code point between frames. */
    static final class Utf8Decoder {

        private byte[] pending = new byte[0];

        String decode(byte[] payload) {
            if (payload == null || payload.length == 0) {
                return "";
            }
            byte[] combined = new byte[pending.length + payload.length];
            System.arraycopy(pending, 0, combined, 0, pending.length);
            System.arraycopy(payload, 0, combined, pending.length, payload.length);
            int completeEnd = completeUtf8End(combined);
            pending = new byte[combined.length - completeEnd];
            System.arraycopy(combined, completeEnd, pending, 0, pending.length);
            return new String(combined, 0, completeEnd, java.nio.charset.StandardCharsets.UTF_8);
        }

        private static int completeUtf8End(byte[] bytes) {
            int lead = bytes.length - 1;
            int continuation = 0;
            while (lead >= 0 && continuation < 3 && (bytes[lead] & 0xC0) == 0x80) {
                lead--;
                continuation++;
            }
            if (lead < 0) {
                // Only continuation bytes so far; keep them until the lead arrives.
                return 0;
            }
            int first = bytes[lead] & 0xFF;
            int needed = first < 0x80 ? 1 : first >= 0xF0 ? 4 : first >= 0xE0 ? 3 : first >= 0xC0 ? 2 : 1;
            return bytes.length - lead < needed ? lead : bytes.length;
        }
    }
}
