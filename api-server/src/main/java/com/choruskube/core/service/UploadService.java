package com.choruskube.core.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
public class UploadService {

    private static final Logger logger = LoggerFactory.getLogger(UploadService.class);

    private final S3Client s3Client;
    private final ObjectMapper objectMapper;

    @Value("${objectstore.bucket}")
    private String bucket;

    public UploadService(S3Client s3Client, ObjectMapper objectMapper) {
        this.s3Client = s3Client;
        this.objectMapper = objectMapper;
    }

    /**
     * Upload files to a temporary staging area before a run is created.
     * Path: {orgSlug}/staging/{stagingUUID}/{sanitisedFilename}
     * Returns a JSON string: {"filename": "objectKey", ...}
     */
    public String uploadTempFiles(String orgSlug, List<MultipartFile> files) throws Exception {
        String stagingId = UUID.randomUUID().toString();
        Map<String, String> refs = new LinkedHashMap<>();
        for (MultipartFile file : files) {
            String safeName = sanitiseFilename(file.getOriginalFilename());
            String objectKey = orgSlug + "/staging/" + stagingId + "/" + safeName;
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType(
                                    file.getContentType() != null ? file.getContentType() : "application/octet-stream")
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            refs.put(safeName, objectKey);
        }
        return objectMapper.writeValueAsString(refs);
    }

    /**
     * Move staged attachments from {@code {orgSlug}/staging/{stagingId}/...} into the
     * run-scoped prefix {@code {orgSlug}/runs/{runId}/inputs/...} and return a rewritten
     * refs JSON pointing at the new keys. Staging copies are deleted after a successful
     * copy (best-effort: a delete failure is logged but does not fail the move — the
     * orphan can be reclaimed by a bucket lifecycle rule).
     *
     * <p>Why move at all: the agent's per-execution presign scope only allows paths
     * under {@code {orgSlug}/runs/{runId}/}, so staging refs handed to the agent
     * verbatim are rejected with 403.
     *
     * @param stagingRefsJson JSON map of {@code {filename: stagingObjectKey, ...}}
     * @return rewritten JSON with the same filenames pointing at the run-scoped keys
     */
    public String copyStagingToRun(String orgSlug, UUID runId, String stagingRefsJson) throws Exception {
        Map<String, String> stagingRefs =
                objectMapper.readValue(stagingRefsJson, new TypeReference<Map<String, String>>() {});
        if (stagingRefs.isEmpty()) {
            return stagingRefsJson;
        }

        Map<String, String> newRefs = new LinkedHashMap<>();
        String runPrefix = orgSlug + "/runs/" + runId + "/inputs/";

        // Phase 1: copy all. If any copy throws, abort before any deletes — the
        // staging objects are still intact, so the caller can retry the run-create.
        for (Map.Entry<String, String> entry : stagingRefs.entrySet()) {
            String filename = entry.getKey();
            String stagingKey = entry.getValue();
            String runKey = runPrefix + filename;
            s3Client.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(bucket)
                    .sourceKey(stagingKey)
                    .destinationBucket(bucket)
                    .destinationKey(runKey)
                    .build());
            newRefs.put(filename, runKey);
        }

        // Phase 2: delete staging copies. Best-effort — a failed delete here doesn't
        // invalidate the move (the run-scoped copies already exist), and a stranded
        // staging object is harmless.
        for (String stagingKey : stagingRefs.values()) {
            try {
                s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucket)
                        .key(stagingKey)
                        .build());
            } catch (Exception e) {
                logger.warn(
                        "Failed to delete staging object {} after copy to run {}: {}",
                        stagingKey,
                        runId,
                        e.getMessage());
            }
        }

        return objectMapper.writeValueAsString(newRefs);
    }

    /**
     * Upload files for a gate review decision.
     * Path: {orgSlug}/runs/{runId}/gate-attachments/{nodeExecId}/{sanitisedFilename}
     * Returns a JSON string: {"filename": "objectKey", ...}
     */
    public String uploadGateFiles(String orgSlug, UUID runId, UUID nodeExecId, List<MultipartFile> files)
            throws Exception {
        Map<String, String> refs = new LinkedHashMap<>();
        for (MultipartFile file : files) {
            String safeName = sanitiseFilename(file.getOriginalFilename());
            String objectKey = orgSlug + "/runs/" + runId + "/gate-attachments/" + nodeExecId + "/" + safeName;
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType(
                                    file.getContentType() != null ? file.getContentType() : "application/octet-stream")
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            refs.put(safeName, objectKey);
        }
        return objectMapper.writeValueAsString(refs);
    }

    /**
     * Strip directory separators and replace characters outside [a-zA-Z0-9._-] with underscores.
     * Avoids {@code Paths.get} to prevent {@code InvalidPathException} on non-ASCII filenames
     * in environments where the filesystem locale does not support Unicode paths.
     */
    private String sanitiseFilename(String original) {
        if (original == null || original.isEmpty()) return "file";
        // Strip any directory component (handles both / and \ separators)
        int lastSep = Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\'));
        String name = lastSep >= 0 ? original.substring(lastSep + 1) : original;
        if (name.isEmpty()) name = "file";
        // Replace characters outside the safe ASCII set with underscores
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
