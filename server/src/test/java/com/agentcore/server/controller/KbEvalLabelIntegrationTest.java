package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.service.KbKnowledgeBaseService;
import com.agentcore.server.support.AbstractKbIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Eval-label seam: a label pins one question to one passage of one KB. Its
 * text fingerprint makes a re-chunked passage visible as {@code rotten}
 * instead of silently pointing at the wrong content.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.kb.ingest-consumer-enabled=false")
class KbEvalLabelIntegrationTest extends AbstractKbIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KbKnowledgeBaseService knowledgeBaseService;

    @Test
    void createsALabelAndListsItWithItsPassageIdentity() throws Exception {
        User user = newUser();
        String kbId = knowledgeBaseService.defaultId(user.id());
        String documentId = seedDocument(user.id(), kbId, 3);

        postLabel(user.token(), kbId, "巡检周期是多少", documentId, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("巡检周期是多少"))
                .andExpect(jsonPath("$.seq").value(2))
                .andExpect(jsonPath("$.page").value(3))
                .andExpect(jsonPath("$.title").value("labels.pdf"))
                .andExpect(jsonPath("$.state").value("ok"));

        listLabels(user.token(), kbId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.limit").value(100))
                .andExpect(jsonPath("$.items[0].state").value("ok"))
                .andExpect(jsonPath("$.items[0].documentId").value(documentId));
    }

    @Test
    void overridesTheTargetWhenTheSameQueryIsLabeledAgain() throws Exception {
        User user = newUser();
        String kbId = knowledgeBaseService.defaultId(user.id());
        String documentId = seedDocument(user.id(), kbId, 3);

        postLabel(user.token(), kbId, "同一个问题", documentId, 1).andExpect(status().isOk());
        postLabel(user.token(), kbId, "同一个问题", documentId, 3).andExpect(status().isOk());

        listLabels(user.token(), kbId)
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].seq").value(3));
    }

    @Test
    void rejectsADocumentFromAnotherKnowledgeBase() throws Exception {
        User user = newUser();
        String firstKb = knowledgeBaseService.defaultId(user.id());
        String otherKb = knowledgeBaseService.create(user.id(), "另一个库").getId();
        String documentInOtherKb = seedDocument(user.id(), otherKb, 2);

        postLabel(user.token(), firstKb, "越库标注", documentInOtherKb, 1)
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsAPassageThatDoesNotExist() throws Exception {
        User user = newUser();
        String kbId = knowledgeBaseService.defaultId(user.id());
        String documentId = seedDocument(user.id(), kbId, 2);

        postLabel(user.token(), kbId, "不存在的片段", documentId, 99)
                .andExpect(status().isNotFound());
    }

    @Test
    void marksRottenWhenThePassageTextChanges() throws Exception {
        User user = newUser();
        String kbId = knowledgeBaseService.defaultId(user.id());
        String documentId = seedDocument(user.id(), kbId, 2);
        postLabel(user.token(), kbId, "会腐化的问题", documentId, 1).andExpect(status().isOk());

        jdbc.update(
                "UPDATE kb_passages SET content = ? WHERE document_id = ? AND seq = ?",
                "重建后完全不同的内容",
                documentId,
                1);

        listLabels(user.token(), kbId)
                .andExpect(jsonPath("$.items[0].state").value("rotten"));
    }

    @Test
    void enforcesTheHundredLabelCapButStillAllowsOverrides() throws Exception {
        User user = newUser();
        String kbId = knowledgeBaseService.defaultId(user.id());
        String documentId = seedDocument(user.id(), kbId, 2);
        for (int i = 0; i < 100; i++) {
            jdbc.update("""
                    INSERT INTO kb_eval_labels (user_id, kb_id, query, document_id, seq,
                        passage_sha256)
                    VALUES (?, ?, ?, ?, 1, ?)
                    """, user.id(), kbId, "问题-" + i, documentId, "f".repeat(64));
        }

        postLabel(user.token(), kbId, "第 101 个问题", documentId, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("KB_EVAL_LABEL_LIMIT"));
        postLabel(user.token(), kbId, "问题-0", documentId, 2).andExpect(status().isOk());
    }

    @Test
    void deletesOnlyTheOwnersLabel() throws Exception {
        User owner = newUser();
        User stranger = newUser();
        String kbId = knowledgeBaseService.defaultId(owner.id());
        String documentId = seedDocument(owner.id(), kbId, 2);
        String body = postLabel(owner.token(), kbId, "可删除的问题", documentId, 1)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long labelId = ((Number) objectMapper.readValue(body, Map.class).get("id")).longValue();

        mockMvc.perform(delete("/api/kb/retrieval/labels/" + labelId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + stranger.token()))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/kb/retrieval/labels/" + labelId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.token()))
                .andExpect(status().isNoContent());

        listLabels(owner.token(), kbId).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void rejectsBlankQueries() throws Exception {
        User user = newUser();
        String kbId = knowledgeBaseService.defaultId(user.id());
        String documentId = seedDocument(user.id(), kbId, 1);

        postLabel(user.token(), kbId, "   ", documentId, 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void createsANoAnswerLabelWithoutAPassageTarget() throws Exception {
        User user = newUser();
        String kbId = knowledgeBaseService.defaultId(user.id());
        seedDocument(user.id(), kbId, 1);

        postNoAnswerLabel(user.token(), kbId, "库里没有的问题")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectation").value("no_answer"))
                .andExpect(jsonPath("$.state").value("ok"))
                .andExpect(jsonPath("$.documentId").doesNotExist())
                .andExpect(jsonPath("$.seq").doesNotExist());

        listLabels(user.token(), kbId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].expectation").value("no_answer"))
                .andExpect(jsonPath("$.items[0].state").value("ok"));
    }

    @Test
    void rejectsANoAnswerLabelThatCarriesATarget() throws Exception {
        User user = newUser();
        String kbId = knowledgeBaseService.defaultId(user.id());
        String documentId = seedDocument(user.id(), kbId, 1);

        String body = objectMapper.writeValueAsString(Map.of(
                "kbId", kbId,
                "query", "矛盾的标注",
                "expectation", "no_answer",
                "documentId", documentId,
                "seq", 1));
        mockMvc.perform(post("/api/kb/retrieval/labels")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void requiresATargetForAnAnswerLabel() throws Exception {
        User user = newUser();
        String kbId = knowledgeBaseService.defaultId(user.id());

        String body = objectMapper.writeValueAsString(
                Map.of("kbId", kbId, "query", "缺少目标"));
        mockMvc.perform(post("/api/kb/retrieval/labels")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void reusesTheSameQuerySlotWhenSwitchingToNoAnswer() throws Exception {
        User user = newUser();
        String kbId = knowledgeBaseService.defaultId(user.id());
        String documentId = seedDocument(user.id(), kbId, 2);

        postLabel(user.token(), kbId, "同一个问题", documentId, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectation").value("answer"));
        postNoAnswerLabel(user.token(), kbId, "同一个问题")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectation").value("no_answer"));

        listLabels(user.token(), kbId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].expectation").value("no_answer"));
    }

    private ResultActions postNoAnswerLabel(String token, String kbId, String query)
            throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("kbId", kbId, "query", query, "expectation", "no_answer"));
        return mockMvc.perform(post("/api/kb/retrieval/labels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions postLabel(
            String token, String kbId, String query, String documentId, int seq) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("kbId", kbId, "query", query, "documentId", documentId, "seq", seq));
        return mockMvc.perform(post("/api/kb/retrieval/labels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions listLabels(String token, String kbId) throws Exception {
        return mockMvc.perform(get("/api/kb/retrieval/labels")
                .param("kbId", kbId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private String seedDocument(long userId, String kbId, int passageCount) {
        String documentId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO kb_documents (id, user_id, kb_id, title, source_type, original_key,
                    original_sha256, mime, size_bytes, status, chunk_count, version,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'labels.pdf', 'upload', ?, ?, 'application/pdf', 100, 'ready', ?, 0,
                    NOW(6), NOW(6))
                """,
                documentId,
                userId,
                kbId,
                userId + "/" + documentId + "/labels.pdf",
                "e".repeat(64),
                passageCount);
        for (int seq = 1; seq <= passageCount; seq++) {
            String content = "标注测试段落 seq-" + seq;
            jdbc.update("""
                    INSERT INTO kb_passages (document_id, user_id, seq, page, content, char_count)
                    VALUES (?, ?, ?, 3, ?, ?)
                    """, documentId, userId, seq, content, content.length());
        }
        return documentId;
    }

    private User newUser() throws Exception {
        String email = "kb-labels-" + UUID.randomUUID() + "@example.com";
        long userId = AuthTestCalls.register(mockMvc, email, "correct-horse");
        return new User(userId, AuthTestCalls.login(mockMvc, email, "correct-horse"));
    }

    private record User(long id, String token) {}
}
