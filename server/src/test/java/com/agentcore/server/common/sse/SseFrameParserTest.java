package com.agentcore.server.common.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SseFrameParserTest {

    @Test
    void parsesFieldsAndPreservesRawBlock() throws Exception {
        String input = """
                id: run-1:2
                event: text_delta
                data: {"delta":"ok"}

                """;
        SseFrameParser parser = new SseFrameParser(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));

        SseFrame frame = parser.read();

        assertEquals("run-1:2", frame.id());
        assertEquals("text_delta", frame.event());
        assertEquals("{\"delta\":\"ok\"}", frame.data());
        assertEquals(input, frame.raw());
        assertNull(parser.read());
    }

    @Test
    void keepsHeartbeatAsACommentFrame() throws Exception {
        String input = ": ping\n\n";
        SseFrameParser parser = new SseFrameParser(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));

        SseFrame frame = parser.read();

        assertNull(frame.event());
        assertNull(frame.data());
        assertEquals(input, frame.raw());
    }

    @Test
    void joinsMultilineData() throws Exception {
        String input = "event: note\ndata: first\ndata: second\n\n";
        SseFrameParser parser = new SseFrameParser(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));

        assertEquals("first\nsecond", parser.read().data());
    }
}
