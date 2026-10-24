package com.agentcore.server.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.agentcore.server.common.crypto.ApiKeyCipher;
import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.kb.KbAvailability;
import com.agentcore.server.common.kb.KbEmbeddingClient;
import com.agentcore.server.common.kb.KbModelsClient;
import com.agentcore.server.common.kb.KbRerankClient;
import com.agentcore.server.common.kb.KbRerankSettings;
import com.agentcore.server.service.KbKnowledgeBaseService;
import com.agentcore.server.common.kb.KbUrlGuard;
import com.agentcore.server.common.vector.VectorStore;
import com.agentcore.server.config.KbProperties;
import com.agentcore.server.config.ModelProperties;
import com.agentcore.server.dao.KbDocumentDao;
import com.agentcore.server.dao.KbUserSettingsDao;
import com.agentcore.server.dto.kb.KbSettingsUpdateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * The outbound guard is part of the settings write/probe seam: a rejected
 * endpoint must fail before the service touches the probe client or the
 * settings row.
 */
class KbSettingsServiceImplTest {

    private static final long USER_ID = 7L;

    @Test
    void rejectsAPrivateEndpointBeforeProbingOrSaving() {
        Fixture fixture = fixture(false);

        assertThatThrownBy(() -> fixture.service.update(
                        USER_ID, request("https://127.0.0.1:8443/v1")))
                .isInstanceOfSatisfying(ApiException.class, (error) ->
                        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_REQUEST));

        verify(fixture.settingsDao).selectById(USER_ID);
        verifyNoMoreInteractions(fixture.settingsDao);
    }

    @Test
    void rejectsAPrivateEndpointOnProbeToo() {
        Fixture fixture = fixture(false);

        assertThatThrownBy(() -> fixture.service.probe(
                        USER_ID, request("https://127.0.0.1:8443/v1")))
                .isInstanceOfSatisfying(ApiException.class, (error) ->
                        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_REQUEST));

        verify(fixture.settingsDao).selectById(USER_ID);
        verifyNoMoreInteractions(fixture.settingsDao);
    }

    @Test
    void alwaysRejectsTheCloudMetadataAddress() {
        Fixture fixture = fixture(true);

        assertThatThrownBy(() -> fixture.service.update(
                        USER_ID, request("https://169.254.169.254/latest")))
                .isInstanceOfSatisfying(ApiException.class, (error) ->
                        assertThat(error.code()).isEqualTo(ErrorCode.INVALID_REQUEST));

        verify(fixture.settingsDao).selectById(USER_ID);
        verifyNoMoreInteractions(fixture.settingsDao);
    }

    private static KbSettingsUpdateRequest request(String baseUrl) {
        return new KbSettingsUpdateRequest(
                new KbSettingsUpdateRequest.Embedding(
                        baseUrl, "sk-test", "test-embedding-model", 1024, "/embeddings"),
                null);
    }

    private static Fixture fixture(boolean allowPrivateEndpoints) {
        KbProperties properties = new KbProperties(
                true,
                allowPrivateEndpoints,
                200,
                1L << 30,
                3,
                new KbProperties.Embedding(
                        "https://embedding.example.com/v1", "key", "model", 1024, 32, "/embeddings"),
                null);
        KbUserSettingsDao settingsDao = mock(KbUserSettingsDao.class);
        KbUrlGuard guard = new KbUrlGuard(properties);
        ObjectMapper objectMapper = new ObjectMapper();
        KbSettingsServiceImpl service = new KbSettingsServiceImpl(
                new KbAvailability(properties),
                settingsDao,
                mock(KbDocumentDao.class),
                new KbEmbeddingClient(objectMapper, guard),
                new KbRerankClient(objectMapper, guard),
                new KbModelsClient(objectMapper),
                new KbRerankSettings(properties, settingsDao, new ApiKeyCipher(new ModelProperties(
                        "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", false))),
                mock(KbKnowledgeBaseService.class),
                guard,
                new ApiKeyCipher(new ModelProperties(
                        "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", false)),
                properties,
                mock(VectorStore.class),
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        return new Fixture(service, settingsDao);
    }

    private record Fixture(KbSettingsServiceImpl service, KbUserSettingsDao settingsDao) {}
}
