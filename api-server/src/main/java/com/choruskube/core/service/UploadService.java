package com.choruskube.core.service;

import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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

    /** The staging segment of a staged key, {@code {prefix}/staging/{stagingId}/{file}}. */
    public static final String STAGING_SEGMENT = "staging/";

    /**
     * Fixed 400 body for a run start's refs: names no key and reads the same whether or not the
     * named object exists, so it teaches a forger nothing about another tree.
     */
    public static final String INPUT_REFS_REFUSED =
            "inputAttachmentRefs must each name a file in your own staging area, under a plain file name";

    private final S3Client s3Client;
    private final ObjectMapper objectMapper;
    private final StoragePrefixResolver storagePrefixResolver;
    private final WorkflowRunRepository runRepo;

    @Value("${objectstore.bucket}")
    private String bucket;

    public UploadService(
            S3Client s3Client,
            ObjectMapper objectMapper,
            StoragePrefixResolver storagePrefixResolver,
            WorkflowRunRepository runRepo) {
        this.s3Client = s3Client;
        this.objectMapper = objectMapper;
        this.storagePrefixResolver = storagePrefixResolver;
        this.runRepo = runRepo;
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
            String objectKey = orgSlug + "/" + STAGING_SEGMENT + stagingId + "/" + safeName;
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
     * Returns refs in the shape a run start's {@code inputAttachmentRefs} consumes — the JSON
     * {@link #admitStagingRefs} parses before {@link #copyStagingToRun} ever sees the entries. The
     * prefix is the current tenant's, so an off-request caller must run inside a bound org scope.
     */
    public String stageTexts(Map<String, String> contentByFileName) {
        if (contentByFileName.isEmpty()) {
            return "{}";
        }
        String orgSlug = storagePrefixResolver.currentStoragePrefix();
        String stagingId = UUID.randomUUID().toString();
        Map<String, String> refs = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : contentByFileName.entrySet()) {
            String safeName = sanitiseFilename(entry.getKey());
            String objectKey = orgSlug + "/" + STAGING_SEGMENT + stagingId + "/" + safeName;
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType("text/markdown")
                            .build(),
                    RequestBody.fromString(entry.getValue(), StandardCharsets.UTF_8));
            refs.put(safeName, objectKey);
        }
        try {
            return objectMapper.writeValueAsString(refs);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise staging refs", e);
        }
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
     * @param admittedRefs {@code {filename: stagingObjectKey, ...}} already passed {@link
     *     #admitStagingRefs} — this method does not re-check them
     * @return rewritten JSON with the same filenames pointing at the run-scoped keys
     */
    public String copyStagingToRun(String orgSlug, UUID runId, Map<String, String> admittedRefs) throws Exception {
        if (admittedRefs.isEmpty()) {
            return objectMapper.writeValueAsString(admittedRefs);
        }

        Map<String, String> newRefs = new LinkedHashMap<>();
        String runPrefix = orgSlug + "/runs/" + runId + "/inputs/";

        // Phase 1: copy all. If any copy throws, abort before any deletes — the
        // staging objects are still intact, so the caller can retry the run-create.
        for (Map.Entry<String, String> entry : admittedRefs.entrySet()) {
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
        for (String stagingKey : admittedRefs.values()) {
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
     * Admits a run start's {@code inputAttachmentRefs} value: every key must sit in the caller's
     * own staging area (see {@link #isStagingKey}) and every name must be a plain file name (see
     * {@link #isPlainFileName}), or the whole value is refused with {@link #INPUT_REFS_REFUSED}
     * before anything is written, read or deleted. Parses the value exactly once; {@link
     * #copyStagingToRun} is handed only what this returns and never re-reads the raw value, so no
     * ref can reach the move without having passed this check.
     *
     * @param refsJson the request's raw {@code inputAttachmentRefs}
     * @return the admitted entries, in request order; empty for a null, blank or {@code {}} value,
     *     which also skips resolving the caller's storage prefix
     */
    public Map<String, String> admitStagingRefs(String refsJson) {
        if (refsJson == null || refsJson.isBlank()) {
            return Map.of();
        }
        JsonNode refs;
        try {
            refs = objectMapper.readTree(refsJson);
        } catch (Exception e) {
            throw new BadRequestException(INPUT_REFS_REFUSED);
        }
        if (!refs.isObject()) {
            throw new BadRequestException(INPUT_REFS_REFUSED);
        }
        if (refs.isEmpty()) {
            return Map.of();
        }

        String prefix = storagePrefixResolver.currentStoragePrefix();
        Map<String, String> admitted = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = refs.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String name = entry.getKey();
            JsonNode value = entry.getValue();
            if (!value.isTextual() || !isStagingKey(prefix, value.asText()) || !isPlainFileName(name)) {
                throw new BadRequestException(INPUT_REFS_REFUSED);
            }
            admitted.put(name, value.asText());
        }
        return admitted;
    }

    /**
     * Whether {@code key} sits inside {@code prefix}'s own staging area: under {@code prefix +
     * "/" + STAGING_SEGMENT}, with at least one character below it, and no {@code ..} anywhere.
     * The {@code /} after {@code prefix} is part of the test so a sibling prefix that begins with
     * this one (e.g. {@code acme-api} against {@code acme}) never admits this prefix's keys.
     */
    static boolean isStagingKey(String prefix, String key) {
        if (key == null || key.contains("..")) {
            return false;
        }
        String stagingRoot = prefix + "/" + STAGING_SEGMENT;
        return key.startsWith(stagingRoot) && key.length() > stagingRoot.length();
    }

    /**
     * Whether {@code name} is a plain file name: non-null, non-empty, not a dot segment, and exactly
     * what {@link #sanitiseFilename} would have produced from it. A client composing refs by hand is
     * therefore held to the upload sanitiser's own alphabet.
     */
    static boolean isPlainFileName(String name) {
        if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return false;
        }
        return name.equals(sanitiseFilename(name));
    }

    /**
     * Replaces a run input in place. The workflow handed its pods the input keys once, at start, and
     * each pod downloads by key, so every node that runs after this reads the new content.
     */
    public void overwriteRunInput(UUID runId, String fileName, String content) {
        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        String objectKey = readRefs(run.getInputArtifactRefs()).get(fileName);
        if (objectKey == null) {
            throw new IllegalStateException("Run " + runId + " has no input " + fileName + " to overwrite");
        }
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .contentType("text/markdown")
                        .build(),
                RequestBody.fromString(content, StandardCharsets.UTF_8));
    }

    /**
     * Best-effort delete of staged files no run took; never throws, since callers run it while
     * propagating the failure that refused the run. A starter retried on every reconcile pass would
     * otherwise leave a copy behind each time it is refused.
     */
    public void discardStaged(String stagingRefsJson) {
        if (stagingRefsJson == null || stagingRefsJson.isBlank()) {
            return;
        }
        Map<String, String> staged;
        try {
            staged = readRefs(stagingRefsJson);
        } catch (RuntimeException e) {
            logger.warn("Could not read the staging refs to discard: {}", e.getMessage());
            return;
        }
        for (String stagingKey : staged.values()) {
            try {
                s3Client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucket)
                        .key(stagingKey)
                        .build());
            } catch (Exception e) {
                logger.warn("Failed to delete unused staging object {}: {}", stagingKey, e.getMessage());
            }
        }
    }

    private Map<String, String> readRefs(String refsJson) {
        try {
            return objectMapper.readValue(refsJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Malformed artifact refs", e);
        }
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

    /** Stores {@code content} as a gate attachment, keyed like {@link #uploadGateFiles}. */
    public String uploadGateText(String orgSlug, UUID runId, UUID nodeExecId, String filename, String content) {
        return putGateText(orgSlug + "/runs/" + runId + "/gate-attachments/" + nodeExecId, filename, content);
    }

    /**
     * Like the form above, with {@code attemptId} in the object key so concurrent submissions at
     * one gate never write the same object. The returned ref still names the file {@code filename}.
     */
    public String uploadGateText(
            String orgSlug, UUID runId, UUID nodeExecId, UUID attemptId, String filename, String content) {
        return putGateText(
                orgSlug + "/runs/" + runId + "/gate-attachments/" + nodeExecId + "/" + attemptId, filename, content);
    }

    private String putGateText(String keyPrefix, String filename, String content) {
        String safeName = sanitiseFilename(filename);
        String objectKey = keyPrefix + "/" + safeName;
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .contentType("text/markdown")
                        .build(),
                RequestBody.fromString(content, StandardCharsets.UTF_8));
        try {
            return objectMapper.writeValueAsString(Map.of(safeName, objectKey));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise attachment refs", e);
        }
    }

    /**
     * Strip directory separators and replace characters outside [a-zA-Z0-9._-] with underscores.
     * Avoids {@code Paths.get} to prevent {@code InvalidPathException} on non-ASCII filenames
     * in environments where the filesystem locale does not support Unicode paths.
     */
    static String sanitiseFilename(String original) {
        if (original == null || original.isEmpty()) return "file";
        // Strip any directory component (handles both / and \ separators)
        int lastSep = Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\'));
        String name = lastSep >= 0 ? original.substring(lastSep + 1) : original;
        if (name.isEmpty()) name = "file";
        // RunStorageScope refuses `..` anywhere in a key, so a name keeping one would yield refs
        // that presign and every artifact-recording write then reject.
        String sanitised = name.replaceAll("[^a-zA-Z0-9._-]", "_").replaceAll("\\.{2,}", ".");
        // A name made only of dots collapses to "." above, which isPlainFileName refuses as a dot
        // segment — fall back to the same placeholder used for an empty name.
        return ".".equals(sanitised) ? "file" : sanitised;
    }
}
