package com.agentcore.server.common.auth;

import com.agentcore.server.config.GatewayProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates gateway tool callbacks with the shared service token
 * (ADR-0007). User identity for those calls still comes from the
 * {@code X-User-Id} headers validated by the controllers.
 *
 * <p>The dev-only pass-through (no token configured) is allowed for local
 * development but fails startup under the {@code prod} profile.
 */
public class ToolTokenFilter extends OncePerRequestFilter {

    /** Ant patterns shared with the security matcher so both cannot drift. */
    public static final String[] PATTERNS = {"/api/kb", "/api/kb/**", "/api/exec"};

    private static final Logger log = LoggerFactory.getLogger(ToolTokenFilter.class);

    private final GatewayProperties gateway;

    public ToolTokenFilter(GatewayProperties gateway, Environment environment) {
        this.gateway = gateway;
        if (!gateway.hasToken()) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("GATEWAY_API_TOKEN is required in prod");
            }
            log.warn("GATEWAY_API_TOKEN is not set; tool callbacks are unauthenticated (dev only)");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isToolPath(request.getRequestURI());
    }

    static boolean isToolPath(String path) {
        return path.equals("/api/kb") || path.startsWith("/api/kb/") || path.equals("/api/exec");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!gateway.hasToken()) {
            authenticateAsTool();
            chain.doFilter(request, response);
            return;
        }

        String token = BearerToken.extract(request.getHeader("Authorization"));
        if (token != null && constantTimeEquals(token, gateway.apiToken())) {
            authenticateAsTool();
        }
        chain.doFilter(request, response);
    }

    private static void authenticateAsTool() {
        var authentication = new UsernamePasswordAuthenticationToken(
                "gateway", null, List.of(new SimpleGrantedAuthority("TOOL")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static boolean constantTimeEquals(String provided, String expected) {
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
