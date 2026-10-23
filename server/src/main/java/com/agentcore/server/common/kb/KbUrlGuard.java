package com.agentcore.server.common.kb;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.KbProperties;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Minimal outbound URL guard for HTML ingestion. Public HTTPS is the default;
 * {@code KB_ALLOW_PRIVATE_ENDPOINTS=true} permits private addresses and HTTP.
 * Cloud metadata addresses are rejected in both modes. DNS is fully resolved
 * before the request; rebinding remains a residual risk (spec Risks).
 */
@Component
public class KbUrlGuard {

    private final KbProperties properties;

    public KbUrlGuard(KbProperties properties) {
        this.properties = properties;
    }

    public URI validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw invalid("url is required");
        }
        if (rawUrl.length() > KbFileTypes.MAX_URL_LENGTH) {
            throw invalid("url must be at most " + KbFileTypes.MAX_URL_LENGTH + " characters");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw invalid("url is malformed");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw invalid("url must use http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw invalid("url must contain a host");
        }
        if (uri.toString().length() > KbFileTypes.MAX_URL_LENGTH) {
            throw invalid("url must be at most " + KbFileTypes.MAX_URL_LENGTH + " characters");
        }
        if (uri.getUserInfo() != null) {
            throw invalid("url must not contain credentials");
        }
        if (!properties.allowPrivateEndpoints() && !scheme.equals("https")) {
            throw invalid("url must use https");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw invalid("url host cannot be resolved");
        }
        for (InetAddress address : addresses) {
            if (isMetadata(address)) {
                throw invalid("url host is not allowed");
            }
            if (!properties.allowPrivateEndpoints() && isPrivate(address)) {
                throw invalid("url host is not allowed");
            }
        }
        return uri;
    }

    private static boolean isPrivate(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            // Carrier-grade NAT 100.64.0.0/10.
            return (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64;
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            // Unique local fc00::/7.
            return (bytes[0] & 0xfe) == 0xfc;
        }
        return false;
    }

    private static boolean isMetadata(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return matches(bytes, 169, 254, 169, 254)
                    || matches(bytes, 169, 254, 170, 2)
                    || matches(bytes, 100, 100, 100, 200);
        }
        return address.getHostAddress().equalsIgnoreCase("fd00:ec2::254");
    }

    private static boolean matches(byte[] bytes, int... expected) {
        if (bytes.length != expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[i] & 0xff) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_REQUEST, message);
    }
}
