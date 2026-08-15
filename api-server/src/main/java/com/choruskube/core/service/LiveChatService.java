package com.choruskube.core.service;

import com.choruskube.core.dto.CompleteLiveChatRequest;
import com.choruskube.core.dto.LiveChatMessageResponse;
import com.choruskube.core.dto.LiveChatSessionResponse;
import com.choruskube.core.event.MappableCreated;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.executor.ExecutionParams;
import com.choruskube.core.executor.ExecutionResult;
import com.choruskube.core.executor.IdentitySpec;
import com.choruskube.core.executor.WorkloadExecutor;
import com.choruskube.core.model.LiveChatMessage;
import com.choruskube.core.model.LiveChatSession;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.LiveChatStatus;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.repository.LiveChatMessageRepository;
import com.choruskube.core.repository.LiveChatSessionRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.NodeExecutionUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LiveChatService {

    private static final Logger log = LoggerFactory.getLogger(LiveChatService.class);

    private final LiveChatSessionRepository sessionRepo;
    private final LiveChatMessageRepository messageRepo;
    private final NodeExecutionRepository execRepo;
    private final WorkflowRunRepository runRepo;
    private final RunEventPublisher eventPublisher;
    private final GraphSnapshotBuilder snapshotBuilder;
    private final ObjectMapper objectMapper;
    private final WorkloadExecutor executor;
    private final String defaultAgentImage;
    private final String apiServerUrl;
    private final AuthorizationService authService;
    private final StoragePrefixResolver storagePrefixResolver;
    private final ApplicationEventPublisher applicationEventPublisher;

    public LiveChatService(
            LiveChatSessionRepository sessionRepo,
            LiveChatMessageRepository messageRepo,
            NodeExecutionRepository execRepo,
            WorkflowRunRepository runRepo,
            RunEventPublisher eventPublisher,
            GraphSnapshotBuilder snapshotBuilder,
            ObjectMapper objectMapper,
            WorkloadExecutor executor,
            @Qualifier("executorDefaultAgentImage") String defaultAgentImage,
            @Qualifier("executorApiServerUrl") String apiServerUrl,
            AuthorizationService authService,
            StoragePrefixResolver storagePrefixResolver,
            ApplicationEventPublisher applicationEventPublisher) {
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
        this.execRepo = execRepo;
        this.runRepo = runRepo;
        this.eventPublisher = eventPublisher;
        this.snapshotBuilder = snapshotBuilder;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.defaultAgentImage = defaultAgentImage;
        this.apiServerUrl = apiServerUrl;
        this.authService = authService;
        this.storagePrefixResolver = storagePrefixResolver;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * Start a live chat session for an awaiting_human gate node.
     * Finds the nearest completed AI predecessor as the source for session resume,
     * then spawns a chat pod via the workload executor.
     */
    @Transactional
    public LiveChatSessionResponse startLiveChat(UUID runId, UUID nodeExecId) {
        NodeExecution gateExec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));

        // Verify the node execution belongs to the requested run before starting a chat session.
        NodeExecutionUtil.requireInRun(gateExec, runId);

        if (gateExec.getStatus() != NodeExecutionStatus.awaiting_human) {
            throw new BadRequestException("Node must be in awaiting_human status to start live chat");
        }

        // Check for existing active/pending session
        Optional<LiveChatSession> existing = sessionRepo.findByNodeExecutionIdAndStatusIn(
                nodeExecId, List.of(LiveChatStatus.pending, LiveChatStatus.active));
        if (existing.isPresent()) {
            throw new ConflictException("An active live chat session already exists for this node");
        }

        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        authService.checkOrgAccess("workflow_run", runId);

        // Build graph snapshot to resolve repo and image
        String repoUrl = null;
        String image = defaultAgentImage;
        JsonNode snapshot = null;
        try {
            String snapshotJson = snapshotBuilder.buildSnapshotForRun(run);
            snapshot = objectMapper.readTree(snapshotJson);

            // Extract repo URL from snapshot inputs
            JsonNode inputs = snapshot.path("inputs");
            repoUrl = inputs.path("repo_url").asText(null);

            // Resolve image: run input agent_image → system default
            if (inputs.has("agent_image")
                    && !inputs.path("agent_image").asText("").isBlank()) {
                image = inputs.path("agent_image").asText();
            }
        } catch (Exception e) {
            log.warn("Failed to build snapshot for live chat, falling back to defaults: {}", e.getMessage());
        }

        // Derive working branch (same pattern as orchestrator dag_executor.go)
        String workingBranch = null;
        if (repoUrl != null && !repoUrl.isBlank()) {
            workingBranch = "choruskube-run-" + runId;
        }

        // Build github_token_url for git credential support
        String githubTokenUrl =
                apiServerUrl + "/internal/runs/" + runId + "/node-executions/" + nodeExecId + "/github-token";

        // Build run_log_path so the AI has context from prior nodes
        String orgSlug = storagePrefixResolver.storagePrefixForRun(runId);
        String runLogPath = orgSlug + "/runs/" + runId + "/run_log.md";

        // Find nearest completed AI predecessor
        UUID sourceExecId = findSourceAINodeExecution(run, gateExec, snapshot);

        LiveChatSession session = new LiveChatSession();
        session.setNodeExecutionId(nodeExecId);
        session.setWorkflowRunId(runId);
        session.setSourceNodeExecutionId(sourceExecId);
        session.setStatus(LiveChatStatus.pending);
        session = sessionRepo.save(session);
        applicationEventPublisher.publishEvent(MappableCreated.withParent(
                "live_chat_session", session.getId(), "workflow_run", session.getWorkflowRunId()));

        // Build config.json for the chat pod with workspace fields
        Map<String, Object> configJson = new LinkedHashMap<>();
        configJson.put("mode", "live_chat");
        configJson.put("session_id", session.getId().toString());
        configJson.put("run_id", runId.toString());
        configJson.put("node_execution_id", nodeExecId.toString());
        configJson.put("api_server_url", apiServerUrl);
        if (sourceExecId != null) {
            configJson.put("source_node_execution_id", sourceExecId.toString());
        }
        if (repoUrl != null && !repoUrl.isBlank()) {
            configJson.put("repo_url", repoUrl);
        }
        if (workingBranch != null && !workingBranch.isBlank()) {
            configJson.put("working_branch", workingBranch);
        }
        configJson.put("github_token_url", githubTokenUrl);
        configJson.put("run_log_path", runLogPath);

        // Spawn the chat pod (session ID doubles as the ephemeral execution ID)
        ExecutionResult result = executor.execute(new ExecutionParams(
                session.getId(),
                runId,
                gateExec.getTemplateNodeId(),
                image,
                configJson,
                false,
                List.of(),
                IdentitySpec.empty()));

        // Update session with pod name
        session.setChatPodName(result.executionHandle());
        sessionRepo.save(session);

        // Update gate node: status to live_chat, store JOB_SECRET hash for pod auth
        gateExec.setStatus(NodeExecutionStatus.live_chat);
        gateExec.setJobSecretHash(result.jobSecretHash());
        execRepo.save(gateExec);
        eventPublisher.publishNodeStatusChanged(runId, nodeExecId, "live_chat");

        // Note: run-level status stays as-is (e.g. awaiting_human). The orchestrator
        // owns run-level status transitions; the node-level live_chat status is sufficient.

        log.info(
                "Started live chat session {} for node {} in run {}, pod={}",
                session.getId(),
                nodeExecId,
                runId,
                result.executionHandle());
        return toResponse(session);
    }

    /**
     * Get the strictly active/pending live chat session for a node execution.
     * Used by internal (chat pod) endpoints that need the active session.
     */
    public LiveChatSessionResponse getActiveSession(UUID nodeExecId) {
        LiveChatSession session = sessionRepo
                .findByNodeExecutionIdAndStatusIn(nodeExecId, List.of(LiveChatStatus.pending, LiveChatStatus.active))
                .orElseThrow(() -> new NotFoundException("No active live chat session for node: " + nodeExecId));
        return toResponse(session);
    }

    /**
     * Get the current session for a node execution, preferring active/pending
     * but falling back to the most recently created session of any status.
     * Used by external (frontend) endpoints so the UI can display completed
     * transcripts and offer "Start Live Chat" again.
     */
    public LiveChatSessionResponse getSessionWithFallback(UUID nodeExecId) {
        checkOrgAccessForNodeExec(nodeExecId);
        // Prefer active/pending session
        Optional<LiveChatSession> active = sessionRepo.findByNodeExecutionIdAndStatusIn(
                nodeExecId, List.of(LiveChatStatus.pending, LiveChatStatus.active));
        if (active.isPresent()) {
            return toResponse(active.get());
        }
        // Fall back to most recent session (any status)
        List<LiveChatSession> all = sessionRepo.findByNodeExecutionIdOrderByCreatedAtDesc(nodeExecId);
        if (all.isEmpty()) {
            throw new NotFoundException("No live chat sessions for node: " + nodeExecId);
        }
        return toResponse(all.get(0));
    }

    /**
     * Get the latest live chat session for a node execution, regardless of status.
     * Returns the most recently created session, or throws NotFoundException if none exist.
     */
    public LiveChatSessionResponse getLatestSession(UUID nodeExecId) {
        checkOrgAccessForNodeExec(nodeExecId);
        List<LiveChatSession> sessions = sessionRepo.findByNodeExecutionIdOrderByCreatedAtDesc(nodeExecId);
        if (sessions.isEmpty()) {
            throw new NotFoundException("No live chat sessions for node: " + nodeExecId);
        }
        return toResponse(sessions.get(0));
    }

    /**
     * Get a live chat session by ID.
     */
    public LiveChatSessionResponse getSession(UUID sessionId) {
        LiveChatSession session = sessionRepo
                .findById(sessionId)
                .orElseThrow(() -> new NotFoundException("Live chat session not found: " + sessionId));
        WorkflowRun run =
                runRepo.findById(session.getWorkflowRunId()).orElseThrow(() -> new NotFoundException("Run not found"));
        authService.checkOrgAccess("workflow_run", run.getId());
        return toResponse(session);
    }

    /**
     * List all sessions for a run.
     */
    public List<LiveChatSessionResponse> listSessionsByRun(UUID runId) {
        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        authService.checkOrgAccess("workflow_run", runId);
        return sessionRepo.findByWorkflowRunIdOrderByCreatedAtDesc(runId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Send a user message in a live chat session. Persists the message and broadcasts via STOMP.
     */
    @Transactional
    public LiveChatMessageResponse sendUserMessage(UUID nodeExecId, String content) {
        checkOrgAccessForNodeExec(nodeExecId);
        LiveChatSession session = sessionRepo
                .findByNodeExecutionIdAndStatusIn(nodeExecId, List.of(LiveChatStatus.pending, LiveChatStatus.active))
                .orElseThrow(() -> new NotFoundException("No active live chat session for node: " + nodeExecId));

        LiveChatMessage message = new LiveChatMessage();
        message.setSessionId(session.getId());
        message.setRole("user");
        message.setContent(content);
        message = messageRepo.save(message);

        // Broadcast the message via STOMP so the pod can receive it
        eventPublisher.publishLiveChatMessage(session.getWorkflowRunId(), session.getId(), "user", content);

        log.debug("User message saved for session {}: {} chars", session.getId(), content.length());
        return toMessageResponse(message);
    }

    /**
     * Save a message from the chat pod (assistant or relayed user message).
     * Called from internal endpoints.
     */
    @Transactional
    public LiveChatMessageResponse saveMessage(UUID sessionId, String role, String content) {
        // Verify session exists
        sessionRepo
                .findById(sessionId)
                .orElseThrow(() -> new NotFoundException("Live chat session not found: " + sessionId));

        LiveChatMessage message = new LiveChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message = messageRepo.save(message);

        // Broadcast via STOMP
        eventPublisher.publishLiveChatMessage(null, sessionId, role, content);

        return toMessageResponse(message);
    }

    /**
     * Get all messages for a session.
     */
    public List<LiveChatMessageResponse> getMessages(UUID sessionId) {
        LiveChatSession session = sessionRepo
                .findById(sessionId)
                .orElseThrow(() -> new NotFoundException("Live chat session not found: " + sessionId));
        WorkflowRun run =
                runRepo.findById(session.getWorkflowRunId()).orElseThrow(() -> new NotFoundException("Run not found"));
        authService.checkOrgAccess("workflow_run", run.getId());
        return messageRepo.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(this::toMessageResponse)
                .toList();
    }

    /**
     * Get messages for a session created at or after a given timestamp (for polling).
     * Uses >= to avoid missing same-millisecond messages; the caller should track
     * the last seen message ID to deduplicate if needed.
     */
    public List<LiveChatMessageResponse> getMessagesSince(UUID sessionId, Instant since) {
        return messageRepo.findBySessionIdAndCreatedAtGreaterThanOrderByCreatedAtAsc(sessionId, since).stream()
                .map(this::toMessageResponse)
                .toList();
    }

    /**
     * Update session status from internal (chat pod) endpoint.
     */
    @Transactional
    public LiveChatSessionResponse updateSession(UUID sessionId, String status, String transcript, String chatPodName) {
        LiveChatSession session = sessionRepo
                .findById(sessionId)
                .orElseThrow(() -> new NotFoundException("Live chat session not found: " + sessionId));

        if (status != null) {
            LiveChatStatus newStatus = LiveChatStatus.valueOf(status);
            session.setStatus(newStatus);
            if (newStatus == LiveChatStatus.active && session.getStartedAt() == null) {
                session.setStartedAt(Instant.now());
            }
            if (newStatus == LiveChatStatus.completed || newStatus == LiveChatStatus.failed) {
                session.setCompletedAt(Instant.now());
            }
        }
        if (transcript != null) {
            session.setTranscript(transcript);
        }
        if (chatPodName != null) {
            session.setChatPodName(chatPodName);
        }

        session = sessionRepo.save(session);

        // Broadcast status change
        eventPublisher.publishLiveChatStatusChanged(
                session.getWorkflowRunId(),
                session.getNodeExecutionId(),
                session.getId(),
                session.getStatus().name());

        return toResponse(session);
    }

    /**
     * Complete a live chat session. Builds the transcript from stored messages
     * and returns the node to awaiting_human status for the human to make a decision.
     */
    @Transactional
    public LiveChatSessionResponse completeLiveChat(UUID runId, UUID nodeExecId, CompleteLiveChatRequest req) {
        // Validates the run exists; the org is no longer needed here (feeds are published org-free,
        // re-scoped downstream via ownership).
        runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        authService.checkOrgAccess("workflow_run", runId);
        LiveChatSession session = sessionRepo
                .findByNodeExecutionIdAndStatusIn(nodeExecId, List.of(LiveChatStatus.pending, LiveChatStatus.active))
                .orElseThrow(() -> new NotFoundException("No active live chat session for node: " + nodeExecId));

        // Build transcript from stored messages, falling back to the request transcript
        String transcript = buildTranscriptFromMessages(session.getId());
        if (transcript == null || transcript.isBlank()) {
            // Fallback to transcript sent from the browser (for backward compatibility)
            transcript = req.transcript();
        }
        if (transcript != null) {
            session.setTranscript(transcript);
        }

        session.setStatus(LiveChatStatus.completed);
        session.setCompletedAt(Instant.now());
        session = sessionRepo.save(session);

        // Terminate the chat pod (best-effort; session ID was used as execution ID). The executor
        // resolves any placement detail it needs from the execution id and is idempotent on a
        // pod that has already been reaped.
        try {
            executor.terminate(session.getId());
        } catch (Exception e) {
            log.warn("Failed to terminate chat pod for session {}: {}", session.getId(), e.getMessage());
        }

        // Set the transcript as the gate node's result and return to awaiting_human
        NodeExecution gateExec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));

        gateExec.setStatus(NodeExecutionStatus.awaiting_human);

        // Accumulate transcripts across sessions: append new transcript to any
        // existing result (from prior sessions) separated by a horizontal rule.
        String previousResult = gateExec.getResult();
        String currentTranscript = session.getTranscript();
        if (previousResult != null
                && !previousResult.isBlank()
                && currentTranscript != null
                && !currentTranscript.isBlank()) {
            gateExec.setResult(previousResult + "\n\n---\n\n" + currentTranscript);
        } else if (currentTranscript != null && !currentTranscript.isBlank()) {
            gateExec.setResult(currentTranscript);
        }
        // If currentTranscript is null/blank, preserve the existing result unchanged

        gateExec.setJobSecretHash(null);
        execRepo.save(gateExec);

        // Note: run-level status is not modified here — the orchestrator owns run-level
        // status transitions. The node returning to awaiting_human is sufficient.

        // Broadcast status change
        eventPublisher.publishNodeStatusChanged(runId, nodeExecId, "awaiting_human");
        eventPublisher.publishLiveChatStatusChanged(runId, nodeExecId, session.getId(), "completed");

        log.info("Completed live chat session {} for node {} in run {}", session.getId(), nodeExecId, runId);
        return toResponse(session);
    }

    /**
     * Build a markdown transcript from stored messages for a session.
     */
    private String buildTranscriptFromMessages(UUID sessionId) {
        List<LiveChatMessage> messages = messageRepo.findBySessionIdOrderByCreatedAtAsc(sessionId);
        if (messages.isEmpty()) {
            return null;
        }
        return messages.stream()
                .map(m -> "**" + ("user".equals(m.getRole()) ? "Human" : "AI") + ":** " + m.getContent())
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * Find the nearest completed AI node execution that is a direct predecessor of the gate node.
     * Reuses a pre-built snapshot if available to avoid redundant snapshot building.
     * Returns null if no AI predecessor is found.
     */
    private UUID findSourceAINodeExecution(WorkflowRun run, NodeExecution gateExec, JsonNode prebuiltSnapshot) {
        try {
            var snapshot = prebuiltSnapshot != null
                    ? prebuiltSnapshot
                    : objectMapper.readTree(snapshotBuilder.buildSnapshotForRun(run));
            var edges = snapshot.get("edges");
            var nodes = snapshot.get("nodes");
            if (edges == null || nodes == null) return null;

            UUID gateNodeId = gateExec.getTemplateNodeId();

            // Find direct predecessor node IDs
            List<String> predecessorNodeIds = new java.util.ArrayList<>();
            for (var edge : edges) {
                if (edge.get("target_node_id").asText().equals(gateNodeId.toString())) {
                    predecessorNodeIds.add(edge.get("source_node_id").asText());
                }
            }

            // Filter to AI nodes
            List<String> aiPredecessorNodeIds = new java.util.ArrayList<>();
            for (String predId : predecessorNodeIds) {
                for (var node : nodes) {
                    if (node.get("template_node_id").asText().equals(predId)
                            && "ai".equals(node.get("executor_type").asText())) {
                        aiPredecessorNodeIds.add(predId);
                    }
                }
            }

            if (aiPredecessorNodeIds.isEmpty()) return null;

            // Find the most recent completed execution for any AI predecessor
            List<NodeExecution> allExecs = execRepo.findByWorkflowRunId(run.getId());
            return allExecs.stream()
                    .filter(e ->
                            aiPredecessorNodeIds.contains(e.getTemplateNodeId().toString()))
                    .filter(e -> e.getStatus() == NodeExecutionStatus.completed)
                    .max(Comparator.comparingInt(NodeExecution::getIteration))
                    .map(NodeExecution::getId)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("Failed to find source AI node execution: {}", e.getMessage());
            return null;
        }
    }

    private void checkOrgAccessForNodeExec(UUID nodeExecId) {
        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));
        WorkflowRun run =
                runRepo.findById(exec.getWorkflowRunId()).orElseThrow(() -> new NotFoundException("Run not found"));
        authService.checkOrgAccess("workflow_run", run.getId());
    }

    private LiveChatSessionResponse toResponse(LiveChatSession s) {
        return new LiveChatSessionResponse(
                s.getId(),
                s.getNodeExecutionId(),
                s.getWorkflowRunId(),
                s.getSourceNodeExecutionId(),
                s.getStatus().name(),
                s.getTranscript(),
                s.getChatPodName(),
                s.getStartedAt(),
                s.getCompletedAt(),
                s.getCreatedAt());
    }

    private LiveChatMessageResponse toMessageResponse(LiveChatMessage m) {
        return new LiveChatMessageResponse(m.getId(), m.getSessionId(), m.getRole(), m.getContent(), m.getCreatedAt());
    }
}
