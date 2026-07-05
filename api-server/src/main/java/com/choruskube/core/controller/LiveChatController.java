package com.choruskube.core.controller;

import com.choruskube.core.dto.*;
import com.choruskube.core.service.LiveChatService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/runs")
public class LiveChatController {

    private final LiveChatService liveChatService;

    public LiveChatController(LiveChatService liveChatService) {
        this.liveChatService = liveChatService;
    }

    /** Start a live chat session for an awaiting_human gate node. */
    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping("/{runId}/nodes/{nodeExecId}/live-chat")
    public ResponseEntity<LiveChatSessionResponse> startLiveChat(
            @PathVariable UUID runId,
            @PathVariable UUID nodeExecId,
            @RequestBody(required = false) StartLiveChatRequest request) {
        LiveChatSessionResponse session = liveChatService.startLiveChat(runId, nodeExecId);
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    /** Get the current live chat session for a gate node (prefers active, falls back to latest). */
    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{runId}/nodes/{nodeExecId}/live-chat")
    public ResponseEntity<LiveChatSessionResponse> getActiveSession(
            @PathVariable UUID runId, @PathVariable UUID nodeExecId) {
        LiveChatSessionResponse session = liveChatService.getSessionWithFallback(nodeExecId);
        return ResponseEntity.ok(session);
    }

    /** Send a user message in an active live chat session. */
    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping("/{runId}/nodes/{nodeExecId}/live-chat/messages")
    public ResponseEntity<LiveChatMessageResponse> sendMessage(
            @PathVariable UUID runId, @PathVariable UUID nodeExecId, @RequestBody SendLiveChatMessageRequest request) {
        LiveChatMessageResponse message = liveChatService.sendUserMessage(nodeExecId, request.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(message);
    }

    /** Complete a live chat session. The node returns to awaiting_human for the human decision. */
    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping("/{runId}/nodes/{nodeExecId}/live-chat/complete")
    public ResponseEntity<LiveChatSessionResponse> completeLiveChat(
            @PathVariable UUID runId, @PathVariable UUID nodeExecId, @RequestBody CompleteLiveChatRequest request) {
        LiveChatSessionResponse session = liveChatService.completeLiveChat(runId, nodeExecId, request);
        return ResponseEntity.ok(session);
    }

    /** Get the latest live chat session for a gate node (any status). */
    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{runId}/nodes/{nodeExecId}/live-chat/latest")
    public ResponseEntity<LiveChatSessionResponse> getLatestSession(
            @PathVariable UUID runId, @PathVariable UUID nodeExecId) {
        LiveChatSessionResponse session = liveChatService.getLatestSession(nodeExecId);
        return ResponseEntity.ok(session);
    }

    /** Get messages for a live chat session. */
    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{runId}/live-chat-sessions/{sessionId}/messages")
    public List<LiveChatMessageResponse> getSessionMessages(@PathVariable UUID runId, @PathVariable UUID sessionId) {
        return liveChatService.getMessages(sessionId);
    }

    /** List all live chat sessions for a run. */
    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{runId}/live-chat-sessions")
    public List<LiveChatSessionResponse> listSessions(@PathVariable UUID runId) {
        return liveChatService.listSessionsByRun(runId);
    }

    /** Get a specific live chat session by ID. */
    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{runId}/live-chat-sessions/{sessionId}")
    public ResponseEntity<LiveChatSessionResponse> getSession(@PathVariable UUID runId, @PathVariable UUID sessionId) {
        LiveChatSessionResponse session = liveChatService.getSession(sessionId);
        return ResponseEntity.ok(session);
    }
}
