package com.choruskube.core.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

class PresignServiceTest {

    private S3Presigner s3Presigner;
    private PresignService service;

    @BeforeEach
    void setUp() {
        s3Presigner = mock(S3Presigner.class);
        service = new PresignService(s3Presigner, "choruskube");
    }

    private void stubGet(String url) throws Exception {
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create(url).toURL());
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);
    }

    private void stubPut(String url) throws Exception {
        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create(url).toURL());
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);
    }

    @Test
    void generatePresignedUrl_GET_callsPresignerWithCorrectArgs() throws Exception {
        stubGet("https://minio:9000/choruskube/runs/abc/out/file.txt?sig=xxx");

        String url = service.generatePresignedUrl("runs/abc/out/file.txt", "GET");

        assertEquals("https://minio:9000/choruskube/runs/abc/out/file.txt?sig=xxx", url);
        verify(s3Presigner).presignGetObject(any(GetObjectPresignRequest.class));
    }

    @Test
    void generatePresignedUrl_PUT_works() throws Exception {
        stubPut("https://minio:9000/choruskube/runs/abc/out/file.txt?sig=yyy");

        String url = service.generatePresignedUrl("runs/abc/out/file.txt", "PUT");

        assertEquals("https://minio:9000/choruskube/runs/abc/out/file.txt?sig=yyy", url);
        verify(s3Presigner).presignPutObject(any(PutObjectPresignRequest.class));
    }

    @Test
    void generatePresignedUrl_invalidMethod_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.generatePresignedUrl("runs/abc/file.txt", "DELETE"));
    }

    @Test
    void generatePresignedUrl_cacheReturnsSameUrl() throws Exception {
        stubGet("https://minio:9000/url1");

        String url1 = service.generatePresignedUrl("runs/abc/file.txt", "GET");
        String url2 = service.generatePresignedUrl("runs/abc/file.txt", "GET");

        assertEquals(url1, url2);
        verify(s3Presigner, times(1)).presignGetObject(any(GetObjectPresignRequest.class));
    }

    @Test
    void generatePresignedUrl_differentMethodsDifferentCacheEntries() throws Exception {
        stubGet("https://get-url");
        stubPut("https://put-url");

        String getUrl = service.generatePresignedUrl("runs/abc/file.txt", "GET");
        String putUrl = service.generatePresignedUrl("runs/abc/file.txt", "PUT");

        assertNotEquals(getUrl, putUrl);
        verify(s3Presigner, times(1)).presignGetObject(any(GetObjectPresignRequest.class));
        verify(s3Presigner, times(1)).presignPutObject(any(PutObjectPresignRequest.class));
    }
}
