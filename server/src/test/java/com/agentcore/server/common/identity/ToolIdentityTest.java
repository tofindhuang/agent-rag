package com.agentcore.server.common.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class ToolIdentityTest {

    @Test
    void readsAllIdentityHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "u1");
        headers.add("X-Session-Id", "s1");
        headers.add("X-Turn-Id", "t1");
        headers.add("X-Run-Id", "r1");
        headers.add("X-Tool-Call-Id", "c1");
        headers.add("X-Request-Id", "q1");

        ToolIdentity identity = ToolIdentity.from(headers);

        assertEquals("u1", identity.userId());
        assertEquals("s1", identity.sessionId());
        assertEquals("t1", identity.turnId());
        assertEquals("r1", identity.runId());
        assertEquals("c1", identity.toolCallId());
        assertEquals("q1", identity.requestId());
    }

    @Test
    void rejectsMissingHeaders() {
        ApiException error = assertThrows(ApiException.class, () -> ToolIdentity.from(new HttpHeaders()));

        assertEquals(ErrorCode.INVALID_REQUEST, error.code());
    }
}
