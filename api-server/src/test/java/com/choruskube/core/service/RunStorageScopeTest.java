package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.exception.BadRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the containment rule presign already applies, shared with every write that
 * records an artifact or attachment reference.
 */
@ExtendWith(MockitoExtension.class)
class RunStorageScopeTest {

    @Mock
    private StoragePrefixResolver storagePrefixResolver;

    private RunStorageScope scope;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID runId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(storagePrefixResolver.storagePrefixForRun(any())).thenReturn("system");
        scope = new RunStorageScope(storagePrefixResolver, objectMapper);
    }

    // ── contains ─────────────────────────────────────────────────────────────

    @Test
    void contains_pathInsideRunsTree_isTrue() {
        assertThat(scope.contains(runId, "system/runs/" + runId + "/x/out/")).isTrue();
    }

    @Test
    void contains_anotherRunsTree_isFalse() {
        UUID otherRun = UUID.randomUUID();
        assertThat(scope.contains(runId, "system/runs/" + otherRun + "/x/out/")).isFalse();
    }

    @Test
    void contains_anotherPrefix_isFalse() {
        assertThat(scope.contains(runId, "other/runs/" + runId + "/x/out/")).isFalse();
    }

    @Test
    void contains_bareRunDirectoryWithNoTrailingSegment_isFalse() {
        assertThat(scope.contains(runId, "system/runs/" + runId)).isFalse();
    }

    @Test
    void contains_dotDotAfterTheRunsTree_isFalse() {
        UUID otherRun = UUID.randomUUID();
        assertThat(scope.contains(runId, "system/runs/" + runId + "/../" + otherRun + "/x"))
                .isFalse();
    }

    @Test
    void contains_dotDotInsideAFileName_isFalse() {
        assertThat(scope.contains(runId, "system/runs/" + runId + "/a..b")).isFalse();
    }

    @Test
    void contains_null_isFalse() {
        assertThat(scope.contains(runId, null)).isFalse();
    }

    @Test
    void contains_blank_isFalse() {
        assertThat(scope.contains(runId, "")).isFalse();
    }

    // ── requireContainedRefs ────────────────────────────────────────────────

    @Test
    void requireContainedRefs_null_passesWithoutResolvingThePrefix() {
        scope.requireContainedRefs(runId, null);

        verify(storagePrefixResolver, never()).storagePrefixForRun(any());
    }

    @Test
    void requireContainedRefs_blank_passesWithoutResolvingThePrefix() {
        scope.requireContainedRefs(runId, "   ");

        verify(storagePrefixResolver, never()).storagePrefixForRun(any());
    }

    @Test
    void requireContainedRefs_emptyObject_passesWithoutResolvingThePrefix() {
        scope.requireContainedRefs(runId, "{}");

        verify(storagePrefixResolver, never()).storagePrefixForRun(any());
    }

    @Test
    void requireContainedRefs_inTreeObject_passes() {
        String refs = "{\"out.md\":\"system/runs/" + runId + "/exec/out.md\"," + "\"log.txt\":\"system/runs/" + runId
                + "/exec/log.txt\"}";

        scope.requireContainedRefs(runId, refs);

        // Resolved once per call, not once per key.
        verify(storagePrefixResolver, times(1)).storagePrefixForRun(runId);
    }

    @Test
    void requireContainedRefs_array_throwsBadRequest() {
        assertThatThrownBy(() -> scope.requireContainedRefs(runId, "[]")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void requireContainedRefs_string_throwsBadRequest() {
        assertThatThrownBy(() -> scope.requireContainedRefs(runId, "\"x\"")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void requireContainedRefs_number_throwsBadRequest() {
        assertThatThrownBy(() -> scope.requireContainedRefs(runId, "42")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void requireContainedRefs_notJson_throwsBadRequest() {
        assertThatThrownBy(() -> scope.requireContainedRefs(runId, "not json")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void requireContainedRefs_valueIsObject_throwsBadRequest() {
        assertThatThrownBy(() -> scope.requireContainedRefs(runId, "{\"out.md\":{\"nested\":\"x\"}}"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void requireContainedRefs_valueIsNumber_throwsBadRequest() {
        assertThatThrownBy(() -> scope.requireContainedRefs(runId, "{\"out.md\":1}"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void requireContainedRefs_valueIsNull_throwsBadRequest() {
        assertThatThrownBy(() -> scope.requireContainedRefs(runId, "{\"out.md\":null}"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void requireContainedRefs_oneOutOfTreeValueAmongInTreeOnes_throwsBadRequest() {
        String refs = "{\"out.md\":\"system/runs/" + runId + "/exec/out.md\"," + "\"other.md\":\"system/runs/"
                + UUID.randomUUID() + "/exec/other.md\"}";

        assertThatThrownBy(() -> scope.requireContainedRefs(runId, refs)).isInstanceOf(BadRequestException.class);
    }
}
