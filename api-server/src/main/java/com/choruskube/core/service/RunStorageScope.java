package com.choruskube.core.service;

import com.choruskube.core.exception.BadRequestException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The containment rule presign already applies to every agent read and write: a path must sit
 * inside the storage tree of the run that owns the execution being written (the run's storage
 * prefix, then the run), with no {@code ..} anywhere. Shared by presign and by every write that
 * records an artifact or attachment reference, so the two can never disagree.
 */
@Component
public class RunStorageScope {

    public static final String NOT_CONTAINED =
            "artifactRefs must be a JSON object naming only paths inside this run's own storage tree";

    private final StoragePrefixResolver storagePrefixResolver;
    private final ObjectMapper objectMapper;

    public RunStorageScope(StoragePrefixResolver storagePrefixResolver, ObjectMapper objectMapper) {
        this.storagePrefixResolver = storagePrefixResolver;
        this.objectMapper = objectMapper;
    }

    /** Whether {@code path} sits inside {@code runId}'s own storage tree, with no {@code ..} anywhere. */
    public boolean contains(UUID runId, String path) {
        return path != null && isInside(treeOf(runId), path);
    }

    /**
     * Refuses a refs value naming anything outside {@code runId}'s own storage tree, before the
     * caller stores or reads it. A null, blank or {@code {}} value carries no paths and returns
     * without resolving the run's prefix, so a follow-up write with no refs always passes.
     */
    public void requireContainedRefs(UUID runId, String refsJson) {
        if (refsJson == null || refsJson.isBlank()) {
            return;
        }
        JsonNode refs;
        try {
            refs = objectMapper.readTree(refsJson);
        } catch (JsonProcessingException e) {
            throw new BadRequestException(NOT_CONTAINED);
        }
        if (!refs.isObject()) {
            throw new BadRequestException(NOT_CONTAINED);
        }
        if (refs.isEmpty()) {
            return;
        }
        String tree = treeOf(runId);
        for (JsonNode value : refs) {
            if (!value.isTextual() || !isInside(tree, value.asText())) {
                throw new BadRequestException(NOT_CONTAINED);
            }
        }
    }

    private String treeOf(UUID runId) {
        return storagePrefixResolver.storagePrefixForRun(runId) + "/runs/" + runId + "/";
    }

    private static boolean isInside(String tree, String path) {
        // `..` is not redundant with the prefix test: `<prefix>/../../other/x` satisfies
        // startsWith and still escapes the run's tree.
        return path.startsWith(tree) && !path.contains("..");
    }
}
