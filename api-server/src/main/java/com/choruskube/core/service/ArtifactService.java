package com.choruskube.core.service;

import com.choruskube.core.dto.ArtifactEntry;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

@Service
public class ArtifactService {

    private final S3Client s3Client;
    private final NodeExecutionRepository execRepo;
    private final WorkflowRunRepository runRepo;
    private final ObjectMapper objectMapper;
    private final AuthorizationService authService;
    private final String bucket;

    public ArtifactService(
            S3Client s3Client,
            NodeExecutionRepository execRepo,
            WorkflowRunRepository runRepo,
            ObjectMapper objectMapper,
            AuthorizationService authService,
            @Value("${objectstore.bucket}") String bucket) {
        this.s3Client = s3Client;
        this.execRepo = execRepo;
        this.runRepo = runRepo;
        this.objectMapper = objectMapper;
        this.authService = authService;
        this.bucket = bucket;
    }

    public List<ArtifactEntry> listArtifacts(UUID runId, UUID execId) {
        checkRunOrgAccess(runId);
        String prefix = resolveOutputPrefix(runId, execId);
        if (prefix == null) {
            return List.of();
        }
        return listEntriesUnder(prefix);
    }

    /**
     * File names under an execution's output prefix, without an org-access check.
     *
     * <p>INTERNAL ONLY. The single caller is the node-completion callback path, which is already
     * authenticated by {@code InternalAuthFilter} on {@code /internal/**} and operates on an
     * execution id it was handed by the orchestrator — the request never carries a tenant context
     * for an org-access check to consult. Do not call this from an {@code /api/**} controller —
     * use {@link #listArtifacts}, which checks org access, instead.
     */
    public List<String> listArtifactNamesInternal(UUID runId, UUID execId) {
        String prefix = resolveOutputPrefix(runId, execId);
        if (prefix == null) {
            return List.of();
        }
        return listEntriesUnder(prefix).stream().map(ArtifactEntry::name).toList();
    }

    /**
     * Flat, recursive listing of every object under {@code prefix}, shared by {@link
     * #listArtifacts} (org-checked) and {@link #listArtifactNamesInternal} (not).
     */
    private List<ArtifactEntry> listEntriesUnder(String prefix) {
        List<ArtifactEntry> entries = new ArrayList<>();
        try {
            // No delimiter → a flat, recursive listing so nested files (e.g.
            // playwright-report/index.html) are surfaced without CommonPrefix entries. The
            // paginator transparently follows continuation tokens, so listings beyond the
            // 1000-key page limit are complete.
            for (S3Object obj : s3Client.listObjectsV2Paginator(ListObjectsV2Request.builder()
                            .bucket(bucket)
                            .prefix(prefix)
                            .build())
                    .contents()) {
                String key = obj.key();
                if (key.equals(prefix)) continue; // skip a 0-byte placeholder at the prefix itself
                String name = key.substring(prefix.length());
                if (name.isEmpty()) continue;
                entries.add(new ArtifactEntry(name, obj.size(), obj.lastModified()));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to list artifacts from object storage", e);
        }
        return entries;
    }

    public String getArtifactContent(UUID runId, UUID execId, String filename) {
        return new String(getArtifactBytes(runId, execId, filename), StandardCharsets.UTF_8);
    }

    public byte[] getArtifactBytes(UUID runId, UUID execId, String filename) {
        checkRunOrgAccess(runId);
        if (!isValidArtifactName(filename)) {
            throw new NotFoundException("Invalid artifact name: " + filename);
        }

        String prefix = resolveOutputPrefix(runId, execId);
        if (prefix == null) {
            throw new NotFoundException("No artifacts for execution: " + execId);
        }

        String objectKey = prefix + filename;
        try (InputStream stream = s3Client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(objectKey).build())) {
            return stream.readAllBytes();
        } catch (NoSuchKeyException e) {
            throw new NotFoundException("Artifact not found: " + filename);
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch artifact from object storage", e);
        }
    }

    /**
     * Allow nested paths like "playwright-report/index.html" but block path traversal.
     * A name is valid if no segment is empty, ".", or "..".
     */
    private static boolean isValidArtifactName(String filename) {
        if (filename == null || filename.isBlank()) return false;
        for (String segment : filename.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private void checkRunOrgAccess(UUID runId) {
        WorkflowRun run = runRepo.findById(runId).orElse(null);
        if (run != null) {
            authService.checkOrgAccess("workflow_run", runId);
        }
    }

    private String resolveOutputPrefix(UUID runId, UUID execId) {
        NodeExecution exec = execRepo.findById(execId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + execId));
        if (!exec.getWorkflowRunId().equals(runId)) {
            throw new NotFoundException("Node execution not found: " + execId);
        }

        String artifactRefs = exec.getArtifactRefs();
        if (artifactRefs == null || artifactRefs.isBlank() || "{}".equals(artifactRefs)) {
            return null;
        }

        try {
            JsonNode node = objectMapper.readTree(artifactRefs);
            JsonNode output = node.get("output");
            if (output == null || output.isNull() || output.asText().isBlank()) {
                return null;
            }
            String prefix = output.asText();
            // Ensure prefix ends with /
            if (!prefix.endsWith("/")) {
                prefix = prefix + "/";
            }
            return prefix;
        } catch (Exception e) {
            return null;
        }
    }
}
