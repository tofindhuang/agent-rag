package com.agentcore.server.kb;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stub for the kb_search tool callback (requirements.md FR-2).
 * The RAG retrieval pipeline (vector store + rerank) lands in M1.
 */
@RestController
@RequestMapping("/api/kb")
public class KbController {

    public record SearchRequest(String query, Integer topK) {}

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody SearchRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query is required");
        }
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                "RAG pipeline lands in M1 (requirements.md FR-2)");
    }
}
