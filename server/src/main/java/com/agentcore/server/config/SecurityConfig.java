package com.agentcore.server.config;

import com.agentcore.server.common.auth.JwtAuthenticationFilter;
import com.agentcore.server.common.auth.JwtService;
import com.agentcore.server.common.auth.ToolTokenFilter;
import com.agentcore.server.common.error.ApiError;
import com.agentcore.server.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Endpoint boundaries (ADR-0007): public auth/health, JWT for chat, shared
 * service token for gateway tool callbacks.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtService jwtService,
            GatewayProperties gateway,
            Environment environment,
            ObjectMapper objectMapper) throws Exception {
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService);
        ToolTokenFilter toolFilter = new ToolTokenFilter(gateway, environment);
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/register",
                                "/api/auth/login",
                                "/api/auth/refresh",
                                "/api/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        .requestMatchers(ToolTokenFilter.PATTERNS).hasAuthority("TOOL")
                        .requestMatchers("/api/auth/me", "/api/auth/change-password", "/api/auth/logout-all",
                                "/api/chat/**", "/api/models/**")
                        .hasAuthority("USER")
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthorized(objectMapper))
                        .accessDeniedHandler(forbidden(objectMapper)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(toolFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Development CORS for the Next.js UI with credentials (refresh cookie in
     * ticket 03). Production should use same-origin deployment.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    private static AuthenticationEntryPoint unauthorized(ObjectMapper objectMapper) {
        return (request, response, ex) -> writeError(
                response, objectMapper, ErrorCode.UNAUTHORIZED, "authentication required");
    }

    private static AccessDeniedHandler forbidden(ObjectMapper objectMapper) {
        return (request, response, ex) -> writeError(
                response, objectMapper, ErrorCode.FORBIDDEN, "access denied");
    }

    private static void writeError(
            HttpServletResponse response, ObjectMapper objectMapper, ErrorCode code, String message)
            throws IOException {
        response.setStatus(code.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ApiError.of(code, message));
    }
}
