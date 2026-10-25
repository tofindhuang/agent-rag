package com.agentcore.server.common.usage;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;

/** Shared window parsing for usage endpoints. */
public final class UsageWindows {

    private UsageWindows() {}

    public static int days(String window) {
        return switch (window) {
            case "7d" -> 7;
            case "30d" -> 30;
            default -> throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "window must be 7d or 30d");
        };
    }
}
