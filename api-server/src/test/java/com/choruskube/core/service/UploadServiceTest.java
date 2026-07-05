package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Unit tests for UploadService using a mocked S3Client.
 */
@ExtendWith(MockitoExtension.class)
class UploadServiceTest {

    @Mock
    private S3Client s3Client;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private UploadService uploadService;

    @BeforeEach
    void setUp() {
        uploadService = new UploadService(s3Client, objectMapper);
        ReflectionTestUtils.setField(uploadService, "bucket", "choruskube-test");
    }

    // ----------------------------
    // uploadTempFiles
    // ----------------------------

    @Test
    void uploadTempFiles_createsObjectAtCorrectPathPrefix() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "report.pdf", "application/pdf", "content".getBytes());

        String result = uploadService.uploadTempFiles("my-org", List.of(file));

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));

        String objectKey = captor.getValue().key();
        assertThat(objectKey).startsWith("my-org/staging/");
        assertThat(objectKey).endsWith("/report.pdf");
        // The staging UUID segment is a valid UUID
        String[] parts = objectKey.split("/");
        assertThat(parts).hasSize(4); // [my-org, staging, uuid, report.pdf]
        assertThatCode(() -> UUID.fromString(parts[2])).doesNotThrowAnyException();
    }

    @Test
    void uploadTempFiles_returnsJsonWithFilenameToObjectKeyMapping() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "data.csv", "text/csv", "a,b,c".getBytes());

        String result = uploadService.uploadTempFiles("acme", List.of(file));

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.has("data.csv")).isTrue();
        assertThat(json.get("data.csv").asText()).startsWith("acme/staging/");
        assertThat(json.get("data.csv").asText()).endsWith("/data.csv");
    }

    @Test
    void uploadTempFiles_emptyFileList_returnsEmptyJson() throws Exception {
        String result = uploadService.uploadTempFiles("acme", List.of());

        assertThat(result).isEqualTo("{}");
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void uploadTempFiles_multipleFiles_allUseTheSameStagingId() throws Exception {
        MockMultipartFile file1 = new MockMultipartFile("files", "a.txt", "text/plain", "a".getBytes());
        MockMultipartFile file2 = new MockMultipartFile("files", "b.txt", "text/plain", "b".getBytes());

        uploadService.uploadTempFiles("org", List.of(file1, file2));

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(2)).putObject(captor.capture(), any(RequestBody.class));

        List<PutObjectRequest> all = captor.getAllValues();
        String stagingId1 = all.get(0).key().split("/")[2];
        String stagingId2 = all.get(1).key().split("/")[2];
        assertThat(stagingId1).isEqualTo(stagingId2);
    }

    // ----------------------------
    // uploadGateFiles
    // ----------------------------

    @Test
    void uploadGateFiles_createsObjectUnderExpectedPath() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID nodeExecId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("files", "photo.png", "image/png", new byte[] {1, 2, 3});

        String result = uploadService.uploadGateFiles("acme", runId, nodeExecId, List.of(file));

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));

        String expected = "acme/runs/" + runId + "/gate-attachments/" + nodeExecId + "/photo.png";
        assertThat(captor.getValue().key()).isEqualTo(expected);
    }

    @Test
    void uploadGateFiles_returnsJsonWithFilenameToObjectKeyMapping() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID nodeExecId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("files", "notes.txt", "text/plain", "notes".getBytes());

        String result = uploadService.uploadGateFiles("acme", runId, nodeExecId, List.of(file));

        JsonNode json = objectMapper.readTree(result);
        assertThat(json.has("notes.txt")).isTrue();
        assertThat(json.get("notes.txt").asText())
                .isEqualTo("acme/runs/" + runId + "/gate-attachments/" + nodeExecId + "/notes.txt");
    }

    @Test
    void uploadGateFiles_emptyFileList_returnsEmptyJson() throws Exception {
        String result = uploadService.uploadGateFiles("acme", UUID.randomUUID(), UUID.randomUUID(), List.of());

        assertThat(result).isEqualTo("{}");
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    // ----------------------------
    // copyStagingToRun
    // ----------------------------

    @Test
    void copyStagingToRun_movesEachStagedObjectIntoRunPrefix() throws Exception {
        UUID runId = UUID.randomUUID();
        String stagingId = UUID.randomUUID().toString();
        String stagingRefs = String.format(
                "{\"a.txt\":\"acme/staging/%s/a.txt\",\"b.png\":\"acme/staging/%s/b.png\"}", stagingId, stagingId);

        String result = uploadService.copyStagingToRun("acme", runId, stagingRefs);

        // Two copies happened, into the run-scoped prefix
        ArgumentCaptor<CopyObjectRequest> copyCaptor = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3Client, times(2)).copyObject(copyCaptor.capture());
        List<String> destKeys = copyCaptor.getAllValues().stream()
                .map(CopyObjectRequest::destinationKey)
                .toList();
        assertThat(destKeys)
                .containsExactlyInAnyOrder(
                        "acme/runs/" + runId + "/inputs/a.txt", "acme/runs/" + runId + "/inputs/b.png");

        // Each staging object was deleted afterward
        ArgumentCaptor<DeleteObjectRequest> rmCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client, times(2)).deleteObject(rmCaptor.capture());
        List<String> removed =
                rmCaptor.getAllValues().stream().map(DeleteObjectRequest::key).toList();
        assertThat(removed)
                .containsExactlyInAnyOrder(
                        "acme/staging/" + stagingId + "/a.txt", "acme/staging/" + stagingId + "/b.png");

        // Returned JSON points at the new keys (filenames preserved)
        JsonNode rewritten = objectMapper.readTree(result);
        assertThat(rewritten.get("a.txt").asText()).isEqualTo("acme/runs/" + runId + "/inputs/a.txt");
        assertThat(rewritten.get("b.png").asText()).isEqualTo("acme/runs/" + runId + "/inputs/b.png");
    }

    @Test
    void copyStagingToRun_emptyRefs_isNoop() throws Exception {
        String result = uploadService.copyStagingToRun("acme", UUID.randomUUID(), "{}");

        assertThat(result).isEqualTo("{}");
        verify(s3Client, never()).copyObject(any(CopyObjectRequest.class));
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void copyStagingToRun_copyFailureMidway_doesNotDeleteAnyStagingObject() throws Exception {
        // First copy succeeds, second copy throws — we want zero deletes so the
        // caller can safely retry from the still-intact staging objects.
        UUID runId = UUID.randomUUID();
        String stagingId = UUID.randomUUID().toString();
        String stagingRefs = String.format(
                "{\"a.txt\":\"acme/staging/%s/a.txt\",\"b.png\":\"acme/staging/%s/b.png\"}", stagingId, stagingId);

        when(s3Client.copyObject(any(CopyObjectRequest.class)))
                .thenReturn(null) // first succeeds
                .thenThrow(new RuntimeException("object store down")); // second fails

        assertThatThrownBy(() -> uploadService.copyStagingToRun("acme", runId, stagingRefs))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("object store down");

        // Critically, no deleteObject was issued — staging is still intact for retry.
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void copyStagingToRun_deleteFailure_isLoggedAndIgnored() throws Exception {
        // A delete failure after a successful copy must NOT fail the move — the
        // run-scoped copy already exists and is what the agent reads.
        UUID runId = UUID.randomUUID();
        String stagingId = UUID.randomUUID().toString();
        String stagingRefs = String.format("{\"a.txt\":\"acme/staging/%s/a.txt\"}", stagingId);

        doThrow(new RuntimeException("delete failed")).when(s3Client).deleteObject(any(DeleteObjectRequest.class));

        String result = uploadService.copyStagingToRun("acme", runId, stagingRefs);

        // Move still reports success and returns the rewritten ref
        JsonNode rewritten = objectMapper.readTree(result);
        assertThat(rewritten.get("a.txt").asText()).isEqualTo("acme/runs/" + runId + "/inputs/a.txt");
    }

    // ----------------------------
    // Filename sanitisation
    // ----------------------------

    @Test
    void sanitise_pathTraversalAttempt_stripsDirectoryPart() throws Exception {
        // "../file.txt" → only "file.txt" is kept (the directory prefix is stripped)
        MockMultipartFile file = new MockMultipartFile("files", "../file.txt", "text/plain", "x".getBytes());

        uploadService.uploadTempFiles("org", List.of(file));

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        String key = captor.getValue().key();
        // The stored filename should be "file.txt" (not "../file.txt")
        assertThat(key).endsWith("/file.txt");
        assertThat(key).doesNotContain("..");
    }

    @Test
    void sanitise_unicodeCharacters_replacedWithUnderscore() throws Exception {
        // Filename with non-ASCII characters: "résumé.pdf"
        // é is outside [a-zA-Z0-9._-] so each é becomes _
        MockMultipartFile file = new MockMultipartFile("files", "résumé.pdf", "application/pdf", "x".getBytes());

        uploadService.uploadTempFiles("org", List.of(file));

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        String key = captor.getValue().key();
        // "résumé.pdf" → "r_sum_.pdf" (each é replaced with _)
        assertThat(key).endsWith("/r_sum_.pdf");
        // The stored key must not contain non-ASCII characters
        assertThat(key).matches("[\\x00-\\x7F]*");
    }

    @Test
    void sanitise_nullOriginalFilename_usesDefaultName() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", null, "application/octet-stream", "x".getBytes());

        uploadService.uploadTempFiles("org", List.of(file));

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        String key = captor.getValue().key();
        assertThat(key).endsWith("/file");
    }

    @Test
    void sanitise_spacesAndSpecialChars_replacedWithUnderscore() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "my file (1).txt", "text/plain", "x".getBytes());

        uploadService.uploadTempFiles("org", List.of(file));

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        String key = captor.getValue().key();
        assertThat(key).endsWith("/my_file__1_.txt");
    }

    @Test
    void sanitise_safeFilename_leftUnchanged() throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", "my-file_v1.0.txt", "text/plain", "x".getBytes());

        uploadService.uploadTempFiles("org", List.of(file));

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        String key = captor.getValue().key();
        assertThat(key).endsWith("/my-file_v1.0.txt");
    }
}
