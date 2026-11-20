package com.agentcore.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.domain.ProfileFact;
import com.agentcore.server.domain.Session;
import com.agentcore.server.dto.profile.ProfileFactCandidate;
import com.agentcore.server.dao.SessionDao;
import com.agentcore.server.dto.profile.ProfileCollectionView;
import com.agentcore.server.support.AbstractIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ProfileFactIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ProfileFactService profileFacts;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProfileCollectionService profileCollections;
    @Autowired
    private SessionDao sessionDao;

    @Test
    void versionsConflictingFactsAndKeepsHistory() throws Exception {
        long userId = newUser();

        assertThat(profileFacts.applyCandidates(userId, "s1", List.of(
                        candidate("preference", "reply.language", "zh-CN", "中文偏好", "用户偏好中文回复")))
                .accepted()).isEqualTo(1);
        assertThat(profileFacts.list(userId, "active", null)).hasSize(1);

        // Same key and value: no new row, no superseded row.
        assertThat(profileFacts.applyCandidates(userId, "s1", List.of(
                        candidate("preference", "reply.language", "zh-CN", "中文偏好", "用户偏好中文回复")))
                .accepted()).isEqualTo(1);
        assertThat(profileFacts.list(userId, "active", null)).hasSize(1);
        assertThat(profileFacts.list(userId, "all", null)).hasSize(1);

        ProfileFactService.ApplyResult changed = profileFacts.applyCandidates(userId, "s2", List.of(
                candidate("preference", "reply.language", "en-US", "英文偏好", "用户偏好英文回复")));
        assertThat(changed.accepted()).isEqualTo(1);
        assertThat(changed.superseded()).isEqualTo(1);

        List<ProfileFact> active = profileFacts.list(userId, "active", null);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getFactValue()).isEqualTo("en-US");
        assertThat(active.get(0).getSupersedesId()).isNotNull();
        assertThat(profileFacts.list(userId, "all", null)).hasSize(2);
    }

    @Test
    void deletedFactsFreeTheKeyForANewActiveVersion() throws Exception {
        long userId = newUser();
        profileFacts.applyCandidates(userId, "s1", List.of(
                candidate("project", "project.database", "MySQL 8", "项目数据库", "项目数据库是 MySQL 8")));
        ProfileFact active = profileFacts.list(userId, "active", null).get(0);

        profileFacts.delete(userId, active.getId());
        assertThat(profileFacts.list(userId, "active", null)).isEmpty();
        assertThat(profileFacts.list(userId, "all", null)).hasSize(1);
        assertThat(profileFacts.list(userId, "all", null).get(0).getStatus()).isEqualTo("deleted");

        profileFacts.applyCandidates(userId, "s2", List.of(
                candidate("project", "project.database", "MySQL 8.4", "项目数据库", "项目数据库是 MySQL 8.4")));
        assertThat(profileFacts.list(userId, "active", null)).hasSize(1);
        assertThat(profileFacts.list(userId, "all", null)).hasSize(2);
    }

    @Test
    void userCanEditDisplayTextAndCategory() throws Exception {
        long userId = newUser();
        profileFacts.applyCandidates(userId, "s1", List.of(
                candidate("preference", "reply.language", "zh-CN", "中文偏好", "用户偏好中文回复")));
        ProfileFact fact = profileFacts.list(userId, "active", null).get(0);

        ProfileFact updated = profileFacts.update(
                userId, fact.getId(), "简体中文偏好", "用户偏好简体中文", "other", null);

        assertThat(updated.getFactText()).isEqualTo("用户偏好简体中文");
        assertThat(updated.getCategory()).isEqualTo("other");
        assertThat(updated.getFactKey()).isEqualTo("reply.language");
        assertThat(updated.getFactValue()).isEqualTo("zh-CN");

        assertThatThrownBy(() -> profileFacts.update(userId, fact.getId(), "bad", "x", "bogus", null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void capsOversizedCandidateBatches() throws Exception {
        long userId = newUser();
        List<ProfileFactCandidate> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            candidates.add(candidate("other", "fact." + i, "v" + i, "fact", "fact " + i));
        }

        ProfileFactService.ApplyResult result =
                profileFacts.applyCandidates(userId, "s1", candidates);

        assertThat(result.dropped()).isEqualTo(5);
        assertThat(profileFacts.list(userId, "active", null)).hasSize(20);
    }

    @Test
    void sessionSelectionFiltersInjectionAndFactsCanMoveCollections() throws Exception {
        long userId = newUser();
        String sessionId = newSession(userId);
        profileFacts.applyCandidates(userId, "s1", List.of(
                candidate("preference", "reply.language", "zh-CN", "中文偏好", "用户偏好中文回复")));
        ProfileFact fact = profileFacts.list(userId, "active", null).get(0);
        assertThat(fact.getFactTitle()).isEqualTo("中文偏好");
        assertThat(fact.getCollectionId()).isNotNull();

        ProfileCollectionView general = profileCollections.list(userId).stream()
                .filter(collection -> "general".equals(collection.systemKey()))
                .findFirst().orElseThrow();
        ProfileCollectionView inbox = profileCollections.list(userId).stream()
                .filter(collection -> "inbox".equals(collection.systemKey()))
                .findFirst().orElseThrow();

        // New facts land in Inbox, so the default General selection sees nothing.
        assertThat(profileFacts.injectionFacts(userId, sessionId)).isEmpty();
        profileCollections.setSessionCollections(userId, sessionId, List.of(inbox.id()));
        assertThat(profileFacts.injectionFacts(userId, sessionId)).hasSize(1);

        ProfileCollectionView custom = profileCollections.create(userId, "项目 A");
        profileFacts.move(userId, List.of(fact.getId()), custom.id());
        assertThat(profileFacts.list(userId, "active", null).get(0).getCollectionId())
                .isEqualTo(custom.id());
        profileCollections.setSessionCollections(userId, sessionId, List.of(general.id()));
        assertThat(profileFacts.injectionFacts(userId, sessionId)).isEmpty();
    }

    @Test
    void deletingACustomCollectionMovesFactsBackToInbox() throws Exception {
        long userId = newUser();
        ProfileCollectionView inbox = profileCollections.list(userId).stream()
                .filter(collection -> "inbox".equals(collection.systemKey()))
                .findFirst().orElseThrow();
        ProfileCollectionView custom = profileCollections.create(userId, "临时场景");
        profileFacts.applyCandidates(userId, "s1", List.of(
                candidate("project", "project.database", "MySQL 8", "项目数据库", "项目数据库是 MySQL 8")));
        ProfileFact fact = profileFacts.list(userId, "active", null).get(0);
        profileFacts.move(userId, List.of(fact.getId()), custom.id());

        profileCollections.delete(userId, custom.id());

        ProfileFact moved = profileFacts.list(userId, "active", null).get(0);
        assertThat(moved.getCollectionId()).isEqualTo(inbox.id());
        assertThat(profileCollections.list(userId))
                .noneMatch(collection -> collection.id().equals(custom.id()));
    }

    @Test
    void sensitiveCandidatesAreDroppedBeforeStorage() throws Exception {
        long userId = newUser();

        ProfileFactService.ApplyResult result = profileFacts.applyCandidates(userId, "s1", List.of(
                candidate("profile", "account.password", "hunter2", "账户密码", "我的密码是 hunter2"),
                candidate("other", "provider.key", "sk-1234567890abcdef1234567890abcdef",
                        "供应商密钥", "我的 API key 是 sk-1234567890abcdef1234567890abcdef"),
                candidate("project", "project.database", "MySQL 8", "项目数据库", "项目数据库是 MySQL 8")));

        assertThat(result.dropped()).isGreaterThanOrEqualTo(2);
        List<ProfileFact> facts = profileFacts.list(userId, "all", null);
        assertThat(facts).extracting(ProfileFact::getFactKey)
                .containsExactly("project.database");
        assertThat(facts).extracting(ProfileFact::getFactText)
                .noneMatch(text -> text.contains("hunter2") || text.contains("sk-"));
    }

    @Test
    void disabledMemoryStopsInjectionAndCandidateWrites() throws Exception {
        long userId = newUser();
        String sessionId = newSession(userId);
        profileFacts.applyCandidates(userId, "s1", List.of(
                candidate("preference", "reply.language", "zh-CN", "中文偏好", "用户偏好中文回复")));
        ProfileCollectionView inbox = profileCollections.list(userId).stream()
                .filter(collection -> "inbox".equals(collection.systemKey()))
                .findFirst().orElseThrow();
        profileCollections.setSessionCollections(userId, sessionId, List.of(inbox.id()));
        assertThat(profileFacts.injectionFacts(userId, sessionId)).hasSize(1);

        profileFacts.setEnabled(userId, false);
        assertThat(profileFacts.enabled(userId)).isFalse();
        assertThat(profileFacts.injectionFacts(userId, sessionId)).isEmpty();
        assertThat(profileFacts.applyCandidates(userId, "s2", List.of(
                        candidate("preference", "reply.language", "en-US", "英文偏好", "用户偏好英文回复")))
                .dropped()).isEqualTo(1);
        assertThat(profileFacts.list(userId, "active", null)).hasSize(1);
    }

    @Test
    void candidateCallbackRequiresTheServiceTokenAndIsUserScoped() throws Exception {
        long userId = newUser();
        String body = """
                {"candidates":[
                  {"category":"preference","factKey":"reply.language",
                   "factValue":"zh-CN","factText":"用户偏好中文回复"}
                ]}
                """;

        mockMvc.perform(post("/api/profile/candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", Long.toString(userId))
                        .content(body))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/profile/candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY_API_TOKEN)
                        .header("X-User-Id", Long.toString(userId))
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1));

        assertThat(profileFacts.list(userId, "active", null)).hasSize(1);
    }

    private String newSession(long userId) {
        String sessionId = UUID.randomUUID().toString();
        Session session = new Session();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setTitle("profile test");
        session.setStatus("active");
        session.setLastActiveAt(LocalDateTime.now(ZoneOffset.UTC));
        sessionDao.insert(session);
        return sessionId;
    }

    private long newUser() throws Exception {
        return AuthTestCalls.register(
                mockMvc, "profile-" + UUID.randomUUID() + "@example.com", "correct-horse");
    }

    private static ProfileFactCandidate candidate(
            String category, String key, String value, String title, String text) {
        return new ProfileFactCandidate(category, key, value, title, text);
    }
}
