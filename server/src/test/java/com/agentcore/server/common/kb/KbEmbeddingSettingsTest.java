package com.agentcore.server.common.kb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentcore.server.common.crypto.ApiKeyCipher;
import com.agentcore.server.config.KbProperties;
import com.agentcore.server.config.ModelProperties;
import com.agentcore.server.dao.KbUserSettingsDao;
import com.agentcore.server.domain.KbUserSettings;
import org.junit.jupiter.api.Test;

class KbEmbeddingSettingsTest {

    @Test
    void fallsBackToTheDeploymentEndpoint() {
        KbEmbeddingSettings settings = new KbEmbeddingSettings(
                properties(true), mock(KbUserSettingsDao.class), cipher());

        KbEmbeddingConfig config = settings.resolve(7);

        assertThat(config.baseUrl()).isEqualTo("https://embedding.example.com/v1");
        assertThat(config.dimension()).isEqualTo(1024);
    }

    @Test
    void userSettingsOverrideTheDeploymentAndDecryptTheKey() {
        KbUserSettingsDao dao = mock(KbUserSettingsDao.class);
        ApiKeyCipher cipher = cipher();
        KbUserSettings user = new KbUserSettings();
        user.setUserId(7L);
        user.setEmbeddingBaseUrl("https://my-embedding.example.com/v1");
        user.setEmbeddingApiKeyCipher(cipher.encrypt(7, "secret"));
        user.setEmbeddingModel("my-model");
        user.setEmbeddingDim(768);
        user.setEmbeddingPath("/vectors");
        when(dao.selectById(7L)).thenReturn(user);

        KbEmbeddingConfig config =
                new KbEmbeddingSettings(properties(true), dao, cipher).resolve(7);

        assertThat(config.baseUrl()).isEqualTo("https://my-embedding.example.com/v1");
        assertThat(config.apiKey()).isEqualTo("secret");
        assertThat(config.model()).isEqualTo("my-model");
        assertThat(config.path()).isEqualTo("/vectors");
        assertThat(config.dimension()).isEqualTo(768);
    }

    @Test
    void failsWhenNoEndpointExistsOrTheKeyCannotBeDecrypted() {
        KbEmbeddingSettings missing = new KbEmbeddingSettings(
                properties(false), mock(KbUserSettingsDao.class), cipher());

        assertThatThrownBy(() -> missing.resolve(7))
                .isInstanceOfSatisfying(KbIngestException.class, (error) -> {
                    assertThat(error.retryable()).isFalse();
                    assertThat(error.errorCode())
                            .isEqualTo(KbIngestException.EMBEDDING_NOT_CONFIGURED);
                });

        KbUserSettingsDao dao = mock(KbUserSettingsDao.class);
        ApiKeyCipher cipher = cipher();
        KbUserSettings user = new KbUserSettings();
        user.setEmbeddingBaseUrl("https://bad-key.example.com/v1");
        user.setEmbeddingApiKeyCipher(cipher.encrypt(8, "other user's key"));
        user.setEmbeddingDim(1024);
        when(dao.selectById(7L)).thenReturn(user);

        assertThatThrownBy(() -> new KbEmbeddingSettings(properties(true), dao, cipher).resolve(7))
                .isInstanceOfSatisfying(KbIngestException.class, (error) ->
                        assertThat(error.errorCode())
                                .isEqualTo(KbIngestException.EMBEDDING_NOT_CONFIGURED));
    }

    private static ApiKeyCipher cipher() {
        return new ApiKeyCipher(new ModelProperties(
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", false));
    }

    private static KbProperties properties(boolean configured) {
        KbProperties.Embedding embedding = configured
                ? new KbProperties.Embedding(
                        "https://embedding.example.com/v1", "key", "model", 1024, 32, "/embeddings")
                : null;
        return new KbProperties(true, true, 200, 1L << 30, 3, embedding, null);
    }
}
