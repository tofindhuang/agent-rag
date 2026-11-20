package com.agentcore.server.common.kb;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.KbProperties;
import org.springframework.stereotype.Component;

/**
 * Single gate for {@code KB_ENABLED=false}. Every KB use case calls
 * {@link #requireEnabled()} before touching storage or credentials so a
 * disabled KB is a clear 409 rather than a connection error.
 */
@Component
public class KbAvailability {

    private final KbProperties properties;

    public KbAvailability(KbProperties properties) {
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public void requireEnabled() {
        if (!properties.enabled()) {
            throw new ApiException(ErrorCode.KB_DISABLED, "knowledge base is disabled");
        }
    }
}
