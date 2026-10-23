package com.agentcore.server.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentcore.server.common.crypto.ApiKeyCipher;
import com.agentcore.server.config.ModelProperties;
import com.agentcore.server.dao.ModelProviderDao;
import com.agentcore.server.dao.ProviderModelDao;
import com.agentcore.server.dao.SessionModelDao;
import com.agentcore.server.dao.UserModelDefaultDao;
import com.agentcore.server.domain.SessionModel;
import com.agentcore.server.service.SessionModelChoice;
import org.junit.jupiter.api.Test;

class SessionModelServiceImplTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void legacySessionsKeepWorkingWhenTheSystemDefaultIsDisabled() {
        SessionModelDao sessionModelDao = mock(SessionModelDao.class);
        when(sessionModelDao.selectById("legacy")).thenReturn(null);
        SessionModelServiceImpl service = service(sessionModelDao, false);

        SessionModelChoice choice = service.forExistingSession(1L, "legacy");

        assertThat(choice.source()).isEqualTo("system");
        assertThat(choice.label()).isEqualTo("系统默认");
    }

    @Test
    void storedSystemSnapshotKeepsItsLabelWithoutTheGate() {
        SessionModelDao sessionModelDao = mock(SessionModelDao.class);
        SessionModel snapshot = new SessionModel();
        snapshot.setSessionId("s1");
        snapshot.setModelLabel("my-system-model");
        snapshot.setModelSource("system");
        when(sessionModelDao.selectById("s1")).thenReturn(snapshot);
        SessionModelServiceImpl service = service(sessionModelDao, false);

        SessionModelChoice choice = service.forExistingSession(1L, "s1");

        assertThat(choice.source()).isEqualTo("system");
        assertThat(choice.label()).isEqualTo("my-system-model");
    }

    private static SessionModelServiceImpl service(SessionModelDao sessionModelDao, boolean allowSystem) {
        ModelProperties properties = new ModelProperties(KEY, allowSystem);
        return new SessionModelServiceImpl(
                mock(ModelProviderDao.class),
                mock(ProviderModelDao.class),
                mock(UserModelDefaultDao.class),
                sessionModelDao,
                new ApiKeyCipher(properties),
                properties);
    }

    @Test
    void factoriesCarryTheSameSemanticsAsTheFlatChoice() {
        SessionModelChoice system = SessionModelChoice.system("系统默认");
        assertThat(system.source()).isEqualTo("system");
        assertThat(system.providerModelId()).isNull();
        assertThat(system.binding()).isNull();
        assertThat(system.usesUserProvider()).isFalse();

        SessionModelChoice.Binding binding = new SessionModelChoice.Binding(
                "Prov", "openai-completions", "https://api.example.com/v1", "sk-key",
                "model-a", "Model A", 65536, true, false);
        SessionModelChoice user = SessionModelChoice.user(7L, "Prov / Model A", binding);

        assertThat(user.source()).isEqualTo("user");
        assertThat(user.providerModelId()).isEqualTo(7L);
        assertThat(user.label()).isEqualTo("Prov / Model A");
        assertThat(user.usesUserProvider()).isTrue();
        assertThat(user.binding()).isSameAs(binding);
    }
}
