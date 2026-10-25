package com.agentcore.server.service.impl;

import com.agentcore.server.common.gateway.GatewayClient;
import com.agentcore.server.common.gateway.SessionTarget;
import com.agentcore.server.service.SessionArchiveGateway;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

/** Gateway implementation of the archive seam; keeps routing in one place. */
@Component
public class GatewayArchiveGateway implements SessionArchiveGateway {

    private final GatewayClient gatewayClient;

    public GatewayArchiveGateway(GatewayClient gatewayClient) {
        this.gatewayClient = gatewayClient;
    }

    @Override
    public ArchiveReply archive(String sessionId, long userId) {
        SessionTarget target = gatewayClient.sessionTarget(sessionId);
        HttpRequest request = gatewayClient.request(
                        target.baseUrl(),
                        "/sessions/" + UriUtils.encodePathSegment(sessionId, StandardCharsets.UTF_8)
                                + "/archive")
                .header("X-User-Id", Long.toString(userId))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        var response = gatewayClient.send(request);
        return new ArchiveReply(response.statusCode(), response.body());
    }
}
