package com.agentcore.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.domain.ProfileFact;
import com.agentcore.server.dto.profile.ProfileFactCandidate;
import com.agentcore.server.support.AbstractIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
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

    @Test
    void versionsConflictingFactsAndKeepsHistory() throws Exception {
        long userId = newUser();

        assertThat(profileFacts.applyCandidates(userId, "s1", List.of(
                        candidate("preference", "reply.language", "zh-CN", "用户偏好中文回复")))
                .accepted()).isEqualTo(1);
        assertThat(profileFacts.list(userId, "active")).hasSize(1);

        // Same key and value: no new row, no superseded row.
        assertThat(profileFacts.applyCandidates(userId, "s1", List.of(
                        candidate("preference", "reply.language", "zh-CN", "用户偏好中文回复")))
                .accepted()).isEqualTo(1);
        assertThat(profileFacts.list(userId, "active")).hasSize(1);
        assertThat(profileFacts.list(userId, "all")).hasSize(1);

        ProfileFactService.ApplyResult changed = profileFacts.applyCandidates(userId, "s2", List.of(
                candidate("preference", "reply.language", "en-US", "用户偏好英文回复")));
        assertThat(changed.accepted()).isEqualTo(1);
        assertThat(changed.superseded()).isEqualTo(1);

        List<ProfileFact> active = profileFacts.list(userId, "active");
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getFactValue()).isEqualTo("en-US");
        assertThat(active.get(0).getSupersedesId()).isNotNull();
        assertThat(profileFacts.list(userId, "all")).hasSize(2);
    }

    @Test
    void deletedFactsFreeTheKeyForANewActiveVersion() throws Exception {
        long userId = newUser();
        profileFacts.applyCandidates(userId, "s1", List.of(
                candidate("project", "project.database", "MySQL 8", "项目数据库是 MySQL 8")));
        ProfileFact active = profileFacts.list(userId, "active").get(0);

        profileFacts.delete(userId, active.getId());
        assertThat(profileFacts.list(userId, "active")).isEmpty();
        assertThat(profileFacts.list(userId, "all")).hasSize(1);
        assertThat(profileFacts.list(userId, "all").get(0).getStatus()).isEqualTo("deleted");

        profileFacts.applyCandidates(userId, "s2", List.of(
                candidate("project", "project.database", "MySQL 8.4", "项目数据库是 MySQL 8.4")));
        assertThat(profileFacts.list(userId, "active")).hasSize(1);
        assertThat(profileFacts.list(userId, "all")).hasSize(2);
    }

    @Test
    void userCanEditDisplayTextAndCategory() throws Exception {
        long userId = newUser();
        profileFacts.applyCandidates(userId, "s1", List.of(
                candidate("preference", "reply.language", "zh-CN", "用户偏好中文回复")));
        ProfileFact fact = profileFacts.list(userId, "active").get(0);

        ProfileFact updated = profileFacts.update(userId, fact.getId(), "用户偏好简体中文", "other");

        assertThat(updated.getFactText()).isEqualTo("用户偏好简体中文");
        assertThat(updated.getCategory()).isEqualTo("other");
        assertThat(updated.getFactKey()).isEqualTo("reply.language");
        assertThat(updated.getFactValue()).isEqualTo("zh-CN");

        assertThatThrownBy(() -> profileFacts.update(userId, fact.getId(), "x", "bogus"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void capsOversizedCandidateBatches() throws Exception {
        long userId = newUser();
        List<ProfileFactCandidate> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            candidates.add(candidate("other", "fact." + i, "v" + i, "fact " + i));
        }

        ProfileFactService.ApplyResult result =
                profileFacts.applyCandidates(userId, "s1", candidates);

        assertThat(result.dropped()).isEqualTo(5);
        assertThat(profileFacts.list(userId, "active")).hasSize(20);
    }

    @Test
    void sensitiveCandidatesAreDroppedBeforeStorage() throws Exception {
        long userId = newUser();

        ProfileFactService.ApplyResult result = profileFacts.applyCandidates(userId, "s1", List.of(
                candidate("profile", "account.password", "hunter2", "我的密码是 hunter2"),
                candidate("other", "provider.key", "sk-1234567890abcdef1234567890abcdef",
                        "我的 API key 是 sk-1234567890abcdef1234567890abcdef"),
                candidate("project", "project.database", "MySQL 8", "项目数据库是 MySQL 8")));

        assertThat(result.dropped()).isGreaterThanOrEqualTo(2);
        List<ProfileFact> facts = profileFacts.list(userId, "all");
        assertThat(facts).extracting(ProfileFact::getFactKey)
                .containsExactly("project.database");
        assertThat(facts).extracting(ProfileFact::getFactText)
                .noneMatch(text -> text.contains("hunter2") || text.contains("sk-"));
    }

    @Test
    void disabledMemoryStopsInjectionAndCandidateWrites() throws Exception {
        long userId = newUser();
        profileFacts.applyCandidates(userId, "s1", List.of(
                candidate("preference", "reply.language", "zh-CN", "用户偏好中文回复")));
        assertThat(profileFacts.injectionFacts(userId)).hasSize(1);

        profileFacts.setEnabled(userId, false);
        assertThat(profileFacts.enabled(userId)).isFalse();
        assertThat(profileFacts.injectionFacts(userId)).isEmpty();
        assertThat(profileFacts.applyCandidates(userId, "s2", List.of(
                        candidate("preference", "reply.language", "en-US", "用户偏好英文回复")))
                .dropped()).isEqualTo(1);
        assertThat(profileFacts.list(userId, "active")).hasSize(1);
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

        assertThat(profileFacts.list(userId, "active")).hasSize(1);
    }

    private long newUser() throws Exception {
        return AuthTestCalls.register(
                mockMvc, "profile-" + UUID.randomUUID() + "@example.com", "correct-horse");
    }

    private static ProfileFactCandidate candidate(
            String category, String key, String value, String text) {
        return new ProfileFactCandidate(category, key, value, text);
    }
}
