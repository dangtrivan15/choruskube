package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class UploadServiceGateTextTest {

    @Test
    void storesTextAsAGateAttachment_keyedLikeUploadedFiles() throws Exception {
        S3Client s3 = mock(S3Client.class);
        UploadService service = new UploadService(s3, new ObjectMapper());
        ReflectionTestUtils.setField(service, "bucket", "artifacts");
        UUID runId = UUID.randomUUID();
        UUID execId = UUID.randomUUID();

        String refs = service.uploadGateText("acme", runId, execId, "human_guidance.md", "# Re-check");

        String key = "acme/runs/" + runId + "/gate-attachments/" + execId + "/human_guidance.md";
        assertThat(new ObjectMapper().readTree(refs).path("human_guidance.md").asText())
                .isEqualTo(key);
        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().bucket()).isEqualTo("artifacts");
        assertThat(put.getValue().key()).isEqualTo(key);
        assertThat(put.getValue().contentType()).isEqualTo("text/markdown");
    }
}
