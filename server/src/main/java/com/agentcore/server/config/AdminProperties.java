package com.agentcore.server.config;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Admin bootstrap allow-list (ADR-0023). The list only promotes accounts; it
 * never demotes, and registration matching an entry creates an admin.
 */
@ConfigurationProperties(prefix = "app.admin")
public record AdminProperties(List<String> emails) {

    public AdminProperties {
        emails = emails == null
                ? List.of()
                : emails.stream()
                        .filter(Objects::nonNull)
                        .map((email) -> email.trim().toLowerCase(Locale.ROOT))
                        .filter((email) -> !email.isEmpty())
                        .distinct()
                        .toList();
    }

    public boolean isAdminEmail(String email) {
        return email != null && emails.contains(email.trim().toLowerCase(Locale.ROOT));
    }
}
