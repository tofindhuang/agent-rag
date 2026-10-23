package com.agentcore.server.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GatewayContractVerifierTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void acceptsContractMajorOne() throws Exception {
        GatewayContractVerifier verifier = verifierFor("{\"contractVersion\":\"1\"}");

        assertDoesNotThrow(() -> verifier.run(null));
    }

    @Test
    void rejectsContractMajorTwo() throws Exception {
        GatewayContractVerifier verifier = verifierFor("{\"contractVersion\":\"2\"}");

        assertThrows(IllegalStateException.class, () -> verifier.run(null));
    }

    @Test
    void skipsCheckWhenGatewayIsUnreachable() {
        GatewayProperties properties = new GatewayProperties("http://127.0.0.1:1", null);
        GatewayContractVerifier verifier = new GatewayContractVerifier(properties, new ObjectMapper());

        assertDoesNotThrow(() -> verifier.run(null));
    }

    private GatewayContractVerifier verifierFor(String responseBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        GatewayProperties properties = new GatewayProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), null);
        return new GatewayContractVerifier(properties, new ObjectMapper());
    }
}
