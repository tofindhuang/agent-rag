package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.support.AbstractKbIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class KbKnowledgeBaseIntegrationTest extends AbstractKbIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void defaultKbIsCreatedAndProtected() throws Exception {
        User user = newUser();

        String list = mockMvc.perform(get("/api/kb/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].isDefault").value(true))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(delete("/api/kb/knowledge-bases/" + user.defaultKbId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isConflict());

        mockMvc.perform(patch("/api/kb/knowledge-bases/" + user.defaultKbId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"工作资料\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("工作资料"))
                .andExpect(jsonPath("$.isDefault").value(true));
    }

    @Test
    void createsRenamesAndDeletesOnlyEmptyNonDefaultKb() throws Exception {
        User user = newUser();

        String created = mockMvc.perform(post("/api/kb/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"项目 A\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isDefault").value(false))
                .andReturn().getResponse().getContentAsString();
        String kbId = JsonPath.read(created, "$.id");

        mockMvc.perform(post("/api/kb/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"项目 A\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(delete("/api/kb/knowledge-bases/" + kbId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isNoContent());
    }

    @Test
    void crossUserKbAccessIsNotFound() throws Exception {
        User owner = newUser();
        User other = newUser();

        mockMvc.perform(patch("/api/kb/knowledge-bases/" + owner.defaultKbId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"hijack\"}"))
                .andExpect(status().isNotFound());
    }

    private User newUser() throws Exception {
        String email = "kb-list-" + UUID.randomUUID() + "@example.com";
        long userId = AuthTestCalls.register(mockMvc, email, "correct-horse");
        String token = AuthTestCalls.login(mockMvc, email, "correct-horse");
        String list = mockMvc.perform(get("/api/kb/knowledge-bases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String defaultKbId = JsonPath.read(list, "$.items[0].id");
        return new User(userId, token, defaultKbId);
    }

    private record User(long id, String token, String defaultKbId) {}
}
