package com.choruskube.core.controller;

import com.choruskube.core.dto.InternalUpdateLiveChatRequest;
import com.choruskube.core.dto.LiveChatMessageEvent;
import com.choruskube.core.dto.LiveChatMessageResponse;
import com.choruskube.core.dto.LiveChatSessionResponse;
import com.choruskube.core.service.LiveChatService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Paths follow the {@code /internal/runs/{runId}/node-executions/{nodeExecId}/...}
 * convention so that {@link com.choruskube.core.config.InternalAuthFilter} can extract
 * the nodeExecId and validate the chat pod's JOB_SECRET against the gate node's
 * {@code job_secret_hash}.
 */
@RestController
@RequestMapping("/internal/runs/{runId}/node-executions/{nodeExecId}/live-chat")
public class InternalLiveChatController {

    private final LiveChatService liveChatService;

    public InternalLiveChatController(LiveChatService liveChatService) {
        this.liveChatService = liveChatService;
    }

    @PutMapping("/session")
    public ResponseEntity<LiveChatSessionResponse> updateSession(
            @PathVariable UUID runId,
            @PathVariable UUID nodeExecId,
            @RequestBody InternalUpdateLiveChatRequest request) {
        LiveChatSessionResponse active = liveChatService.getActiveSession(nodeExecId);
        LiveChatSessionResponse updated = liveChatService.updateSession(
                active.id(), request.status(), request.transcript(), request.chatPodName());
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/session")
    public ResponseEntity<LiveChatSessionResponse> getSession(@PathVariable UUID runId, @PathVariable UUID nodeExecId) {
        return ResponseEntity.ok(liveChatService.getActiveSession(nodeExecId));
    }

    @PostMapping("/messages")
    public ResponseEntity<LiveChatMessageResponse> relayMessage(
            @PathVariable UUID runId, @PathVariable UUID nodeExecId, @RequestBody LiveChatMessageEvent message) {
        LiveChatSessionResponse active = liveChatService.getActiveSession(nodeExecId);
        LiveChatMessageResponse saved = liveChatService.saveMessage(active.id(), message.role(), message.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @GetMapping("/messages")
    public List<LiveChatMessageResponse> getMessages(
            @PathVariable UUID runId, @PathVariable UUID nodeExecId, @RequestParam(required = false) Instant since) {
        LiveChatSessionResponse active = liveChatService.getActiveSession(nodeExecId);
        if (since != null) {
            return liveChatService.getMessagesSince(active.id(), since);
        }
        return liveChatService.getMessages(active.id());
    }
}
