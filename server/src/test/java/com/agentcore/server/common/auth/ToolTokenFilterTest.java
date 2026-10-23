package com.agentcore.server.common.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcore.server.config.GatewayProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class ToolTokenFilterTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesWhenSharedTokenMatches() throws Exception {
        ToolTokenFilter filter = filter("shared-secret");
        MockHttpServletRequest request = kbRequest();
        request.addHeader("Authorization", "Bearer shared-secret");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly("TOOL");
    }

    @Test
    void leavesRequestUnauthenticatedWhenSharedTokenDiffers() throws Exception {
        ToolTokenFilter filter = filter("shared-secret");
        MockHttpServletRequest request = kbRequest();
        request.addHeader("Authorization", "Bearer wrong");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void passesThroughInDevWhenSharedTokenIsNotConfigured() throws Exception {
        ToolTokenFilter filter = filter(null);

        filter.doFilter(kbRequest(), new MockHttpServletResponse(), new MockFilterChain());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly("TOOL");
    }

    @Test
    void refusesToStartWithoutTokenInProd() {
        MockEnvironment prod = new MockEnvironment().withProperty("spring.profiles.active", "prod");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ToolTokenFilter(new GatewayProperties("http://unused", null), prod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GATEWAY_API_TOKEN");
    }

    @Test
    void doesNotTouchNonToolPaths() throws Exception {
        ToolTokenFilter filter = filter("shared-secret");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/sessions");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static ToolTokenFilter filter(String token) {
        return new ToolTokenFilter(new GatewayProperties("http://unused", token), new MockEnvironment());
    }

    private static MockHttpServletRequest kbRequest() {
        return new MockHttpServletRequest("POST", "/api/kb/search");
    }
}
