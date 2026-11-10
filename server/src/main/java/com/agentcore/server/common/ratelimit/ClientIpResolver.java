package com.agentcore.server.common.ratelimit;

import com.agentcore.server.config.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Resolves the client address used for per-IP limits (ADR-0030).
 *
 * <p>{@code X-Forwarded-For} is only trusted when the direct peer is in the
 * configured proxy allow-list, and only its <em>right-most untrusted</em>
 * entry is used: entries to the left can be forged by the client. With no
 * allow-list configured this is exactly {@code request.getRemoteAddr()}.
 */
@Component
public class ClientIpResolver {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private final List<Cidr> trustedProxies;

    public ClientIpResolver(RateLimitProperties properties) {
        List<Cidr> parsed = new ArrayList<>();
        for (String raw : properties.trustedProxies()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            parsed.add(Cidr.parse(raw.trim()));
        }
        this.trustedProxies = List.copyOf(parsed);
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (trustedProxies.isEmpty() || !isTrusted(remote)) {
            return remote;
        }
        String header = request.getHeader(FORWARDED_FOR);
        if (header == null || header.isBlank()) {
            return remote;
        }
        String[] hops = header.split(",");
        for (int index = hops.length - 1; index >= 0; index--) {
            String candidate = hops[index].trim();
            if (!isLiteralAddress(candidate)) {
                continue;
            }
            if (!isTrusted(candidate)) {
                return candidate;
            }
        }
        // Spec (M2 hardening §2): every hop is trusted — take the left-most.
        for (String hop : hops) {
            String candidate = hop.trim();
            if (isLiteralAddress(candidate)) {
                return candidate;
            }
        }
        return remote;
    }

    private boolean isTrusted(String address) {
        byte[] bytes = literalBytes(address);
        if (bytes == null) {
            return false;
        }
        for (Cidr cidr : trustedProxies) {
            if (cidr.matches(bytes)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLiteralAddress(String value) {
        return literalBytes(value) != null;
    }

    /** Only IP literals are accepted: a hostname would trigger DNS during a limit check. */
    private static byte[] literalBytes(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.indexOf(':') >= 0) {
            try {
                return InetAddress.getByName(value).getAddress();
            } catch (UnknownHostException e) {
                return null;
            }
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) {
                return null;
            }
            int octet = 0;
            for (int c = 0; c < part.length(); c++) {
                char ch = part.charAt(c);
                if (ch < '0' || ch > '9') {
                    return null;
                }
                octet = octet * 10 + (ch - '0');
            }
            if (octet > 255) {
                return null;
            }
            bytes[i] = (byte) octet;
        }
        return bytes;
    }

    private record Cidr(byte[] network, int prefixBits) {

        static Cidr parse(String raw) {
            int slash = raw.indexOf('/');
            String host = slash < 0 ? raw : raw.substring(0, slash);
            byte[] bytes = literalBytes(host);
            if (bytes == null) {
                throw new IllegalArgumentException(
                        "invalid trusted proxy address: " + raw);
            }
            int bits;
            if (slash < 0) {
                bits = bytes.length * 8;
            } else {
                try {
                    bits = Integer.parseInt(raw.substring(slash + 1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "invalid trusted proxy prefix: " + raw);
                }
            }
            if (bits < 0 || bits > bytes.length * 8) {
                throw new IllegalArgumentException("invalid trusted proxy prefix: " + raw);
            }
            return new Cidr(bytes, bits);
        }

        boolean matches(byte[] candidate) {
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            int remainingBits = prefixBits % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
