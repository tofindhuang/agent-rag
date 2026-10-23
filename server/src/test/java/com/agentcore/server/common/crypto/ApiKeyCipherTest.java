package com.agentcore.server.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentcore.server.config.ModelProperties;
import org.junit.jupiter.api.Test;

class ApiKeyCipherTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void roundTripsAndBindsToTheUser() {
        ApiKeyCipher cipher = new ApiKeyCipher(new ModelProperties(KEY, true));

        String payload = cipher.encrypt(1L, "sk-secret-1234");

        assertThat(cipher.decrypt(1L, payload)).isEqualTo("sk-secret-1234");
        assertThatThrownBy(() -> cipher.decrypt(2L, payload)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void masksShortAndLongKeys() {
        assertThat(ApiKeyCipher.hint("abc")).isEqualTo("••••");
        assertThat(ApiKeyCipher.hint("sk-secret-1234")).isEqualTo("••••1234");
    }

    @Test
    void rejectsInvalidSecretKeys() {
        assertThatThrownBy(() -> new ApiKeyCipher(new ModelProperties("short", true)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ApiKeyCipher(new ModelProperties(null, true)))
                .isInstanceOf(IllegalStateException.class);
    }
}
