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

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping("/{runId}/nodes/{nodeExecId}/live-chat")
    public ResponseEntity<LiveChatSessionResponse> startLiveChat(
            @PathVariable UUID runId,
            @PathVariable UUID nodeExecId,
            @RequestBody(required = false) StartLiveChatRequest request) {
        LiveChatSessionResponse session = liveChatService.startLiveChat(runId, nodeExecId);
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{runId}/nodes/{nodeExecId}/live-chat")
    public ResponseEntity<LiveChatSessionResponse> getActiveSession(
            @PathVariable UUID runId, @PathVariable UUID nodeExecId) {
        LiveChatSessionResponse session = liveChatService.getSessionWithFallback(nodeExecId);
        return ResponseEntity.ok(session);
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping("/{runId}/nodes/{nodeExecId}/live-chat/messages")
    public ResponseEntity<LiveChatMessageResponse> sendMessage(
            @PathVariable UUID runId, @PathVariable UUID nodeExecId, @RequestBody SendLiveChatMessageRequest request) {
        LiveChatMessageResponse message = liveChatService.sendUserMessage(nodeExecId, request.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(message);
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping("/{runId}/nodes/{nodeExecId}/live-chat/complete")
    public ResponseEntity<LiveChatSessionResponse> completeLiveChat(
            @PathVariable UUID runId, @PathVariable UUID nodeExecId, @RequestBody CompleteLiveChatRequest request) {
        LiveChatSessionResponse session = liveChatService.completeLiveChat(runId, nodeExecId, request);
        return ResponseEntity.ok(session);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{runId}/nodes/{nodeExecId}/live-chat/latest")
    public ResponseEntity<LiveChatSessionResponse> getLatestSession(
            @PathVariable UUID runId, @PathVariable UUID nodeExecId) {
        LiveChatSessionResponse session = liveChatService.getLatestSession(nodeExecId);
        return ResponseEntity.ok(session);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{runId}/live-chat-sessions/{sessionId}/messages")
    public List<LiveChatMessageResponse> getSessionMessages(@PathVariable UUID runId, @PathVariable UUID sessionId) {
        return liveChatService.getMessages(sessionId);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{runId}/live-chat-sessions")
    public List<LiveChatSessionResponse> listSessions(@PathVariable UUID runId) {
        return liveChatService.listSessionsByRun(runId);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{runId}/live-chat-sessions/{sessionId}")
    public ResponseEntity<LiveChatSessionResponse> getSession(@PathVariable UUID runId, @PathVariable UUID sessionId) {
        LiveChatSessionResponse session = liveChatService.getSession(sessionId);
        return ResponseEntity.ok(session);
    }
}
