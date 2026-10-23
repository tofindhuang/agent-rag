package com.agentcore.server.common.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests that carry a valid access token in
 * {@code Authorization: Bearer}. Invalid or missing tokens leave the request
 * unauthenticated; the security chain turns that into a 401 envelope.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = BearerToken.extract(request.getHeader("Authorization"));
        if (token != null) {
            try {
                AuthenticatedUser user = jwtService.verify(token);
                var authentication = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("USER")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtRejectedException ex) {
                // Leave the request unauthenticated; a later filter (tool token)
                // may still authenticate it. Security answers 401 if none did.
            }
        }
        chain.doFilter(request, response);
    }
}
