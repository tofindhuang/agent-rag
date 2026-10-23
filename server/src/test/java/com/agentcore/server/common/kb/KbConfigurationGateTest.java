package com.agentcore.server.common.kb;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.KbProperties;
import com.agentcore.server.config.KbS3Properties;
import com.agentcore.server.dao.KbUserSettingsDao;
import com.agentcore.server.domain.KbUserSettings;
import org.junit.jupiter.api.Test;

class KbConfigurationGateTest {

    @Test
    void failsWhenNeitherTheUserNorTheDeploymentHasAnEmbeddingEndpoint() {
        KbUserSettingsDao settings = mock(KbUserSettingsDao.class);
        KbConfigurationGate gate = new KbConfigurationGate(defaults(), storage(), settings);

        assertThatThrownBy(() -> gate.requireEmbeddingConfigured(7))
                .isInstanceOf(ApiException.class)
                .extracting((error) -> ((ApiException) error).code())
                .isEqualTo(ErrorCode.KB_NOT_CONFIGURED);
    }

    @Test
    void acceptsTheDeploymentFallback() {
        KbProperties properties = new KbProperties(
                true,
                false,
                200,
                1L << 30,
                3,
                new KbProperties.Embedding(
                        "https://embedding.example.com/v1", "key", "model", 1024, 32, "/embeddings"),
                null);
        KbConfigurationGate gate =
                new KbConfigurationGate(properties, storage(), mock(KbUserSettingsDao.class));

        assertThatCode(() -> gate.requireEmbeddingConfigured(7)).doesNotThrowAnyException();
    }

    @Test
    void acceptsAUserEndpoint() {
        KbUserSettingsDao settings = mock(KbUserSettingsDao.class);
        KbUserSettings user = new KbUserSettings();
        user.setEmbeddingBaseUrl("https://my-embedding.example.com/v1");
        user.setEmbeddingDim(1024);
        when(settings.selectById(7L)).thenReturn(user);

        KbConfigurationGate gate = new KbConfigurationGate(defaults(), storage(), settings);

        assertThatCode(() -> gate.requireEmbeddingConfigured(7)).doesNotThrowAnyException();
    }

    private static KbProperties defaults() {
        return new KbProperties(true, false, 200, 1L << 30, 3, null, null);
    }

    private static KbS3Properties storage() {
        return new KbS3Properties(
                "http://localhost:9000", "key", "secret", "kb", "us-east-1", true);
    }
}
