package com.agentcore.server.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.agentcore.server.common.crypto.ApiKeyCipher;
import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.GatewayProperties;
import com.agentcore.server.config.ModelProperties;
import com.agentcore.server.dao.ModelProviderDao;
import com.agentcore.server.dao.ProviderModelDao;
import com.agentcore.server.dao.UserModelDefaultDao;
import com.agentcore.server.domain.ProbeCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ModelProviderServiceImplTest {

    @Test
    void mapsAnUnreachableGatewayToUpstreamUnavailable() {
        ModelProperties modelProperties = new ModelProperties(
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", true);
        ModelProviderServiceImpl service = new ModelProviderServiceImpl(
                mock(ModelProviderDao.class),
                mock(ProviderModelDao.class),
                mock(UserModelDefaultDao.class),
                new ApiKeyCipher(modelProperties),
                modelProperties,
                new GatewayProperties("http://127.0.0.1:1", null),
                new ObjectMapper());

        assertThatThrownBy(() -> service.probe(1L, new ProbeCommand(
                        "openai-completions", "https://api.example.com/v1", "sk-test", null)))
                .isInstanceOfSatisfying(ApiException.class, (error) ->
                        assertThat(error.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE));
    }
}
