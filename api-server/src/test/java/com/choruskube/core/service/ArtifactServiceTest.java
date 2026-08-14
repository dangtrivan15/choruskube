package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

class ArtifactServiceTest {

    private S3Client s3Client;
    private NodeExecutionRepository execRepo;
    private ObjectMapper objectMapper;
    private ArtifactService service;

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID EXEC_ID = UUID.randomUUID();
    private static final String BUCKET = "choruskube";

    @BeforeEach
    void setUp() {
        s3Client = Mockito.mock(S3Client.class);
        execRepo = Mockito.mock(NodeExecutionRepository.class);
        objectMapper = new ObjectMapper();
        service = new ArtifactService(
                s3Client,
                execRepo,
                Mockito.mock(com.choruskube.core.repository.WorkflowRunRepository.class),
                objectMapper,
                new AuthorizationService(new AlwaysAllowAuthorizationStrategy(), false),
                BUCKET);
    }

    private NodeExecution createExecWithArtifacts(String outputPrefix) {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(RUN_ID);
        exec.setArtifactRefs("{\"output\": \"" + outputPrefix + "\"}");
        return exec;
    }

    private static ResponseInputStream<GetObjectResponse> objectStream(byte[] body) {
        return new ResponseInputStream<>(
                GetObjectResponse.builder().build(), AbortableInputStream.create(new ByteArrayInputStream(body)));
    }

    @Test
    void getArtifactBytes_returnsRawBytes() {
        byte[] expected = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47}; // PNG magic bytes
        NodeExecution exec = createExecWithArtifacts("runs/" + RUN_ID + "/" + EXEC_ID + "/out/");
        Mockito.when(execRepo.findById(EXEC_ID)).thenReturn(Optional.of(exec));
        Mockito.when(s3Client.getObject(ArgumentMatchers.<GetObjectRequest>any()))
                .thenReturn(objectStream(expected));

        byte[] result = service.getArtifactBytes(RUN_ID, EXEC_ID, "screenshot.png");

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void getArtifactContent_returnsStringFromBytes() {
        String text = "# Hello World";
        NodeExecution exec = createExecWithArtifacts("runs/" + RUN_ID + "/" + EXEC_ID + "/out/");
        Mockito.when(execRepo.findById(EXEC_ID)).thenReturn(Optional.of(exec));
        Mockito.when(s3Client.getObject(ArgumentMatchers.<GetObjectRequest>any()))
                .thenReturn(objectStream(text.getBytes(StandardCharsets.UTF_8)));

        String result = service.getArtifactContent(RUN_ID, EXEC_ID, "readme.md");

        assertThat(result).isEqualTo(text);
    }

    @Test
    void getArtifactBytes_throwsNotFoundForMissingFile() {
        NodeExecution exec = createExecWithArtifacts("runs/" + RUN_ID + "/" + EXEC_ID + "/out/");
        Mockito.when(execRepo.findById(EXEC_ID)).thenReturn(Optional.of(exec));

        Mockito.when(s3Client.getObject(ArgumentMatchers.<GetObjectRequest>any()))
                .thenThrow(NoSuchKeyException.builder().message("Not found").build());

        assertThatThrownBy(() -> service.getArtifactBytes(RUN_ID, EXEC_ID, "missing.png"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("missing.png");
    }

    @Test
    void getArtifactBytes_rejectsPathTraversal() {
        assertThatThrownBy(() -> service.getArtifactBytes(RUN_ID, EXEC_ID, "../etc/passwd"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Invalid artifact name");

        assertThatThrownBy(() -> service.getArtifactBytes(RUN_ID, EXEC_ID, "report/../../etc/passwd"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Invalid artifact name");

        assertThatThrownBy(() -> service.getArtifactBytes(RUN_ID, EXEC_ID, "report/./file.txt"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Invalid artifact name");

        assertThatThrownBy(() -> service.getArtifactBytes(RUN_ID, EXEC_ID, "report//file.txt"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Invalid artifact name");

        assertThatThrownBy(() -> service.getArtifactBytes(RUN_ID, EXEC_ID, ""))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Invalid artifact name");
    }

    @Test
    void getArtifactBytes_acceptsNestedPath() {
        byte[] expected = "<html></html>".getBytes(StandardCharsets.UTF_8);
        NodeExecution exec = createExecWithArtifacts("runs/" + RUN_ID + "/" + EXEC_ID + "/out/");
        Mockito.when(execRepo.findById(EXEC_ID)).thenReturn(Optional.of(exec));
        Mockito.when(s3Client.getObject(ArgumentMatchers.<GetObjectRequest>any()))
                .thenReturn(objectStream(expected));

        byte[] result = service.getArtifactBytes(RUN_ID, EXEC_ID, "playwright-report/index.html");

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void listArtifacts_flattensNestedFiles() {
        String prefix = "runs/" + RUN_ID + "/" + EXEC_ID + "/out/";
        NodeExecution exec = createExecWithArtifacts(prefix);
        Mockito.when(execRepo.findById(EXEC_ID)).thenReturn(Optional.of(exec));

        List<S3Object> objects = List.of(
                s3Object(prefix + "test_output.txt", 149L),
                s3Object(prefix + "playwright-report/index.html", 584_000L),
                s3Object(prefix + "playwright-report/trace/index.html", 2_200L));
        ListObjectsV2Iterable paginator = Mockito.mock(ListObjectsV2Iterable.class);
        Mockito.when(paginator.contents()).thenReturn(objects::iterator);
        Mockito.when(s3Client.listObjectsV2Paginator(ArgumentMatchers.<ListObjectsV2Request>any()))
                .thenReturn(paginator);

        var entries = service.listArtifacts(RUN_ID, EXEC_ID);

        assertThat(entries)
                .extracting("name")
                .containsExactlyInAnyOrder(
                        "test_output.txt", "playwright-report/index.html", "playwright-report/trace/index.html");
    }

    @Test
    void listArtifactNamesInternal_listsNamesFromGivenArtifactRefs_withNoRepositoryLookup() {
        String prefix = "runs/" + RUN_ID + "/" + EXEC_ID + "/out/";
        String artifactRefs = "{\"output\": \"" + prefix + "\"}";

        List<S3Object> objects = List.of(s3Object(prefix + "escalation.md", 42L), s3Object(prefix + "review.md", 10L));
        ListObjectsV2Iterable paginator = Mockito.mock(ListObjectsV2Iterable.class);
        Mockito.when(paginator.contents()).thenReturn(objects::iterator);
        Mockito.when(s3Client.listObjectsV2Paginator(ArgumentMatchers.<ListObjectsV2Request>any()))
                .thenReturn(paginator);

        List<String> names = service.listArtifactNamesInternal(artifactRefs);

        assertThat(names).containsExactlyInAnyOrder("escalation.md", "review.md");
        // The whole point of this method is to avoid an execId-keyed re-fetch (see its javadoc);
        // confirm it never touches execRepo at all.
        Mockito.verifyNoInteractions(execRepo);
    }

    @Test
    void listArtifactNamesInternal_returnsEmptyForNullArtifactRefs_withNoRepositoryLookup() {
        assertThat(service.listArtifactNamesInternal(null)).isEmpty();
        Mockito.verifyNoInteractions(execRepo, s3Client);
    }

    private static S3Object s3Object(String key, long size) {
        return S3Object.builder()
                .key(key)
                .size(size)
                .lastModified(Instant.parse("2026-04-29T09:29:15Z"))
                .build();
    }
}
