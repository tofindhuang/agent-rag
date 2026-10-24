package com.agentcore.server.common.ratelimit;

import com.agentcore.server.common.auth.AuthenticatedUser;
import com.agentcore.server.common.error.ApiError;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.RateLimitProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-user RPM for authenticated APIs and per-IP RPM for credential endpoints
 * (FR-6). Runs after JWT/ToolToken so identities are already resolved.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> AUTH_PATHS =
            Set.of("/api/auth/login", "/api/auth/register", "/api/auth/refresh");
    private static final Set<String> TOOL_PATHS = Set.of("/api/exec", "/api/kb/search");

    private final RateLimitService rateLimit;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(
            RateLimitService rateLimit,
            RateLimitProperties properties,
            ObjectMapper objectMapper) {
        this.rateLimit = rateLimit;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.enabled() || "OPTIONS".equals(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return "/api/health".equals(path) || !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RateLimitDecision decision = decisionFor(request);
        if (decision != null && !decision.allowed()) {
            writeRateLimited(response, decision.retryAfterSeconds());
            return;
        }
        chain.doFilter(request, response);
    }

    private RateLimitDecision decisionFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (AUTH_PATHS.contains(path) && "POST".equals(request.getMethod())) {
            return rateLimit.checkAuthIpRpm(request.getRemoteAddr());
        }
        if (TOOL_PATHS.contains(path)) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            boolean toolAuthority = authentication != null && authentication.getAuthorities().stream()
                    .anyMatch(authority -> "TOOL".equals(authority.getAuthority()));
            if (!toolAuthority) {
                // Invalid service token: let Security return 401 instead of
                // letting an unauthenticated caller consume a user's quota.
                return null;
            }
            String userId = request.getHeader("X-User-Id");
            if (userId == null || userId.isBlank()) {
                return null;
            }
            try {
                return rateLimit.checkUserRpm(Long.parseLong(userId));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return rateLimit.checkUserRpm(user.userId());
        }
        return null;
    }

    private void writeRateLimited(HttpServletResponse response, long retryAfterSeconds)
            throws IOException {
        response.setStatus(ErrorCode.RATE_LIMITED.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiError.of(
                        ErrorCode.RATE_LIMITED,
                        "request rate limit exceeded; retry in " + retryAfterSeconds + "s"));
    }
}
