package com.agentcore.server.controller;

import com.agentcore.server.common.auth.AuthenticatedUser;
import com.agentcore.server.dao.KbDocumentDao;
import com.agentcore.server.domain.KbKnowledgeBase;
import com.agentcore.server.dto.kb.KbKnowledgeBaseListResponse;
import com.agentcore.server.dto.kb.KbKnowledgeBaseRequest;
import com.agentcore.server.dto.kb.KbKnowledgeBaseView;
import com.agentcore.server.service.KbKnowledgeBaseService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** User-owned knowledge base management (ADR-0016). */
@RestController
@RequestMapping("/api/kb/knowledge-bases")
public class KbKnowledgeBaseController {

    private final KbKnowledgeBaseService kbService;
    private final KbDocumentDao documentDao;

    public KbKnowledgeBaseController(
            KbKnowledgeBaseService kbService, KbDocumentDao documentDao) {
        this.kbService = kbService;
        this.documentDao = documentDao;
    }

    @GetMapping
    public KbKnowledgeBaseListResponse list(@AuthenticationPrincipal AuthenticatedUser user) {
        List<KbKnowledgeBaseView> items = kbService.list(user.userId()).stream()
                .map((kb) -> toView(user.userId(), kb))
                .toList();
        return new KbKnowledgeBaseListResponse(items);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KbKnowledgeBaseView create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody KbKnowledgeBaseRequest request) {
        return toView(user.userId(), kbService.create(user.userId(), request.name()));
    }

    @PatchMapping("/{id}")
    public KbKnowledgeBaseView rename(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String id,
            @RequestBody KbKnowledgeBaseRequest request) {
        return toView(user.userId(), kbService.rename(user.userId(), id, request.name()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
        kbService.delete(user.userId(), id);
    }

    private KbKnowledgeBaseView toView(long userId, KbKnowledgeBase kb) {
        return new KbKnowledgeBaseView(
                kb.getId(),
                kb.getName(),
                Boolean.TRUE.equals(kb.getIsDefault()),
                documentDao.countByKb(userId, kb.getId()));
    }
}
