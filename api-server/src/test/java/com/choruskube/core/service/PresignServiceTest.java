package com.choruskube.core.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.config.ArtifactPresigners;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

class PresignServiceTest {

    private static final URI OWN_ENDPOINT = URI.create("http://seaweedfs:9000");

    private ArtifactPresigners artifactPresigners;
    private S3Presigner ownPresigner;

    @SuppressWarnings("unchecked")
    private ObjectProvider<ArtifactSigningEndpointResolver> endpointResolverProvider = mock(ObjectProvider.class);

    @BeforeEach
    void setUp() {
        artifactPresigners = mock(ArtifactPresigners.class);
        when(artifactPresigners.ownEndpoint()).thenReturn(OWN_ENDPOINT);
        ownPresigner = mock(S3Presigner.class);
        when(artifactPresigners.forEndpoint(OWN_ENDPOINT)).thenReturn(ownPresigner);
    }

    private PresignService serviceWithResolver(ArtifactSigningEndpointResolver resolver) {
        when(endpointResolverProvider.getIfAvailable(any()))
                .thenAnswer(inv -> resolver != null
                        ? resolver
                        : ((Supplier<ArtifactSigningEndpointResolver>) inv.getArgument(0)).get());
        return new PresignService(artifactPresigners, endpointResolverProvider, "choruskube");
    }

    private void stubGet(S3Presigner presigner, String url) throws Exception {
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create(url).toURL());
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);
    }

    private void stubPut(S3Presigner presigner, String url) throws Exception {
        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create(url).toURL());
        when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);
    }

    @Test
    void generatePresignedUrl_GET_callsPresignerWithCorrectArgs() throws Exception {
        PresignService service = serviceWithResolver(null); // no seam bean registered
        stubGet(ownPresigner, "https://minio:9000/choruskube/runs/abc/out/file.txt?sig=xxx");

        String url = service.generatePresignedUrl(UUID.randomUUID(), "runs/abc/out/file.txt", "GET", null);

        assertEquals("https://minio:9000/choruskube/runs/abc/out/file.txt?sig=xxx", url);
        verify(ownPresigner).presignGetObject(any(GetObjectPresignRequest.class));
    }

    @Test
    void generatePresignedUrl_PUT_works() throws Exception {
        PresignService service = serviceWithResolver(null);
        stubPut(ownPresigner, "https://minio:9000/choruskube/runs/abc/out/file.txt?sig=yyy");

        String url = service.generatePresignedUrl(UUID.randomUUID(), "runs/abc/out/file.txt", "PUT", 100L);

        assertEquals("https://minio:9000/choruskube/runs/abc/out/file.txt?sig=yyy", url);
        verify(ownPresigner).presignPutObject(any(PutObjectPresignRequest.class));
    }

    @Test
    void generatePresignedUrl_invalidMethod_throws() {
        PresignService service = serviceWithResolver(null);
        assertThrows(
                IllegalArgumentException.class,
                () -> service.generatePresignedUrl(UUID.randomUUID(), "runs/abc/file.txt", "DELETE", null));
    }

    @Test
    void generatePresignedUrl_PUT_passesContentLengthOnThePutObjectRequest() throws Exception {
        PresignService service = serviceWithResolver(null);
        stubPut(ownPresigner, "https://minio:9000/choruskube/runs/abc/out/file.txt?sig=len");

        service.generatePresignedUrl(UUID.randomUUID(), "runs/abc/out/file.txt", "PUT", 12345L);

        ArgumentCaptor<PutObjectPresignRequest> captor = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(ownPresigner).presignPutObject(captor.capture());
        assertEquals(12345L, captor.getValue().putObjectRequest().contentLength());
    }

    @Test
    void generatePresignedUrl_PUT_nullContentLength_throwsBeforeSigning() {
        PresignService service = serviceWithResolver(null);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.generatePresignedUrl(UUID.randomUUID(), "runs/abc/file.txt", "PUT", null));

        verifyNoInteractions(ownPresigner);
    }

    @Test
    void generatePresignedUrl_PUT_negativeContentLength_throwsBeforeSigning() {
        PresignService service = serviceWithResolver(null);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.generatePresignedUrl(UUID.randomUUID(), "runs/abc/file.txt", "PUT", -1L));

        verifyNoInteractions(ownPresigner);
    }

    @Test
    void generatePresignedUrl_GET_nonNullContentLength_throwsBeforeSigning() {
        PresignService service = serviceWithResolver(null);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.generatePresignedUrl(UUID.randomUUID(), "runs/abc/file.txt", "GET", 100L));

        verifyNoInteractions(ownPresigner);
    }

    @Test
    void generatePresignedUrl_PUT_differentContentLengths_areDifferentCacheEntries() throws Exception {
        PresignService service = serviceWithResolver(null);
        PresignedPutObjectRequest presigned100 = mock(PresignedPutObjectRequest.class);
        PresignedPutObjectRequest presigned200 = mock(PresignedPutObjectRequest.class);
        when(presigned100.url())
                .thenReturn(URI.create("https://minio:9000/size-100").toURL());
        when(presigned200.url())
                .thenReturn(URI.create("https://minio:9000/size-200").toURL());
        when(ownPresigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenReturn(presigned100)
                .thenReturn(presigned200);

        UUID runId = UUID.randomUUID();
        String url1 = service.generatePresignedUrl(runId, "runs/abc/file.txt", "PUT", 100L);
        String url2 = service.generatePresignedUrl(runId, "runs/abc/file.txt", "PUT", 200L);

        assertNotEquals(url1, url2);
        verify(ownPresigner, times(2)).presignPutObject(any(PutObjectPresignRequest.class));
    }

    @Test
    void generatePresignedUrl_PUT_sameContentLength_isCachedAcrossCalls() throws Exception {
        PresignService service = serviceWithResolver(null);
        stubPut(ownPresigner, "https://minio:9000/same-size");

        UUID runId = UUID.randomUUID();
        String url1 = service.generatePresignedUrl(runId, "runs/abc/file.txt", "PUT", 100L);
        String url2 = service.generatePresignedUrl(runId, "runs/abc/file.txt", "PUT", 100L);

        assertEquals(url1, url2);
        verify(ownPresigner, times(1)).presignPutObject(any(PutObjectPresignRequest.class));
    }

    @Test
    void generatePresignedUrl_cacheReturnsSameUrl() throws Exception {
        PresignService service = serviceWithResolver(null);
        stubGet(ownPresigner, "https://minio:9000/url1");

        UUID runId = UUID.randomUUID();
        String url1 = service.generatePresignedUrl(runId, "runs/abc/file.txt", "GET", null);
        String url2 = service.generatePresignedUrl(runId, "runs/abc/file.txt", "GET", null);

        assertEquals(url1, url2);
        verify(ownPresigner, times(1)).presignGetObject(any(GetObjectPresignRequest.class));
    }

    @Test
    void generatePresignedUrl_differentMethodsDifferentCacheEntries() throws Exception {
        PresignService service = serviceWithResolver(null);
        stubGet(ownPresigner, "https://get-url");
        stubPut(ownPresigner, "https://put-url");

        UUID runId = UUID.randomUUID();
        String getUrl = service.generatePresignedUrl(runId, "runs/abc/file.txt", "GET", null);
        String putUrl = service.generatePresignedUrl(runId, "runs/abc/file.txt", "PUT", 100L);

        assertNotEquals(getUrl, putUrl);
        verify(ownPresigner, times(1)).presignGetObject(any(GetObjectPresignRequest.class));
        verify(ownPresigner, times(1)).presignPutObject(any(PutObjectPresignRequest.class));
    }

    @Test
    void generatePresignedUrl_noSeamBean_signsWithOwnEndpoint() throws Exception {
        // ObjectProvider.getIfAvailable falls through to the supplied default when nothing is registered.
        PresignService service = serviceWithResolver(null);
        stubGet(ownPresigner, "https://minio:9000/own-endpoint-url");

        String url = service.generatePresignedUrl(UUID.randomUUID(), "runs/abc/file.txt", "GET", null);

        assertEquals("https://minio:9000/own-endpoint-url", url);
        verify(artifactPresigners).forEndpoint(OWN_ENDPOINT);
    }

    @Test
    void generatePresignedUrl_seamAnswersSecondEndpoint_signsWithThatEndpoint() throws Exception {
        URI otherEndpoint = URI.create("https://artifacts.example.com");
        S3Presigner otherPresigner = mock(S3Presigner.class);
        when(artifactPresigners.forEndpoint(otherEndpoint)).thenReturn(otherPresigner);
        stubGet(otherPresigner, "https://artifacts.example.com/choruskube/runs/abc/file.txt?sig=zzz");

        ArtifactSigningEndpointResolver resolver = runId -> Optional.of(otherEndpoint);
        PresignService service = serviceWithResolver(resolver);

        String url = service.generatePresignedUrl(UUID.randomUUID(), "runs/abc/file.txt", "GET", null);

        assertEquals("https://artifacts.example.com/choruskube/runs/abc/file.txt?sig=zzz", url);
        verify(artifactPresigners).forEndpoint(otherEndpoint);
        verify(artifactPresigners, never()).forEndpoint(OWN_ENDPOINT);
    }

    @Test
    void generatePresignedUrl_cacheIsScopedPerEndpoint_notServedAcrossDifferentRuns() throws Exception {
        URI endpointA = URI.create("https://a.example.com");
        URI endpointB = URI.create("https://b.example.com");
        S3Presigner presignerA = mock(S3Presigner.class);
        S3Presigner presignerB = mock(S3Presigner.class);
        when(artifactPresigners.forEndpoint(endpointA)).thenReturn(presignerA);
        when(artifactPresigners.forEndpoint(endpointB)).thenReturn(presignerB);
        stubGet(presignerA, "https://a.example.com/choruskube/runs/abc/file.txt?sig=aaa");
        stubGet(presignerB, "https://b.example.com/choruskube/runs/abc/file.txt?sig=bbb");

        UUID runA = UUID.randomUUID();
        UUID runB = UUID.randomUUID();
        ArtifactSigningEndpointResolver resolver = runId -> {
            if (runId.equals(runA)) {
                return Optional.of(endpointA);
            }
            if (runId.equals(runB)) {
                return Optional.of(endpointB);
            }
            throw new AssertionError("unexpected runId: " + runId);
        };
        PresignService service = serviceWithResolver(resolver);

        String urlA = service.generatePresignedUrl(runA, "runs/abc/file.txt", "GET", null);
        String urlB = service.generatePresignedUrl(runB, "runs/abc/file.txt", "GET", null);

        assertNotEquals(urlA, urlB);
        verify(artifactPresigners).forEndpoint(endpointA);
        verify(artifactPresigners).forEndpoint(endpointB);
        verify(presignerA, times(1)).presignGetObject(any(GetObjectPresignRequest.class));
        verify(presignerB, times(1)).presignGetObject(any(GetObjectPresignRequest.class));
    }

    @Test
    void generatePresignedUrl_sameEndpointAndKey_isCachedAcrossCalls() throws Exception {
        URI endpoint = URI.create("https://a.example.com");
        S3Presigner presigner = mock(S3Presigner.class);
        when(artifactPresigners.forEndpoint(endpoint)).thenReturn(presigner);
        stubGet(presigner, "https://a.example.com/choruskube/runs/abc/file.txt?sig=ccc");

        ArtifactSigningEndpointResolver resolver = runId -> Optional.of(endpoint);
        PresignService service = serviceWithResolver(resolver);

        String url1 = service.generatePresignedUrl(UUID.randomUUID(), "runs/abc/file.txt", "GET", null);
        String url2 = service.generatePresignedUrl(UUID.randomUUID(), "runs/abc/file.txt", "GET", null);

        assertEquals(url1, url2);
        verify(presigner, times(1)).presignGetObject(any(GetObjectPresignRequest.class));
    }

    @Test
    void generatePresignedUrl_seamThrows_propagatesAndNeverFallsBackToOwnEndpoint() {
        ArtifactSigningEndpointResolver resolver = runId -> {
            throw new IllegalStateException("boom");
        };
        PresignService service = serviceWithResolver(resolver);

        assertThrows(
                IllegalStateException.class,
                () -> service.generatePresignedUrl(UUID.randomUUID(), "runs/abc/file.txt", "GET", null));

        verify(artifactPresigners, never()).forEndpoint(OWN_ENDPOINT);
        verifyNoInteractions(ownPresigner);
    }

    @Test
    void generatePresignedUrl_seamAnswersNonRootPath_throwsBeforeSigning() {
        ArtifactSigningEndpointResolver resolver =
                runId -> Optional.of(URI.create("https://artifacts.example.com/some/path"));
        PresignService service = serviceWithResolver(resolver);

        assertThrows(
                IllegalStateException.class,
                () -> service.generatePresignedUrl(UUID.randomUUID(), "runs/abc/file.txt", "GET", null));

        verifyNoInteractions(ownPresigner);
        verify(artifactPresigners, never()).forEndpoint(any());
    }

    @Test
    void generatePresignedUrl_seamAnswersQuery_throwsBeforeSigning() {
        ArtifactSigningEndpointResolver resolver =
                runId -> Optional.of(URI.create("https://artifacts.example.com?foo=bar"));
        PresignService service = serviceWithResolver(resolver);

        assertThrows(
                IllegalStateException.class,
                () -> service.generatePresignedUrl(UUID.randomUUID(), "runs/abc/file.txt", "GET", null));

        verify(artifactPresigners, never()).forEndpoint(any());
    }

    @Test
    void generatePresignedUrl_seamAnswersFragment_throwsBeforeSigning() {
        ArtifactSigningEndpointResolver resolver =
                runId -> Optional.of(URI.create("https://artifacts.example.com#frag"));
        PresignService service = serviceWithResolver(resolver);

        assertThrows(
                IllegalStateException.class,
                () -> service.generatePresignedUrl(UUID.randomUUID(), "runs/abc/file.txt", "GET", null));

        verify(artifactPresigners, never()).forEndpoint(any());
    }

    @Test
    void generatePresignedUrl_seamAnswersUserinfo_throwsBeforeSigning() {
        ArtifactSigningEndpointResolver resolver =
                runId -> Optional.of(URI.create("https://user:pass@artifacts.example.com"));
        PresignService service = serviceWithResolver(resolver);

        assertThrows(
                IllegalStateException.class,
                () -> service.generatePresignedUrl(UUID.randomUUID(), "runs/abc/file.txt", "GET", null));

        verify(artifactPresigners, never()).forEndpoint(any());
    }

    @Test
    void generatePresignedUrl_seamAnswersNonHttpScheme_throwsBeforeSigning() {
        ArtifactSigningEndpointResolver resolver = runId -> Optional.of(URI.create("ftp://artifacts.example.com"));
        PresignService service = serviceWithResolver(resolver);

        assertThrows(
                IllegalStateException.class,
                () -> service.generatePresignedUrl(UUID.randomUUID(), "runs/abc/file.txt", "GET", null));

        verify(artifactPresigners, never()).forEndpoint(any());
    }

    @Test
    void generatePresignedUrl_seamAnswersNoHost_throwsBeforeSigning() {
        // An opaque http URI parses with a scheme but no host, so only the host check refuses it.
        ArtifactSigningEndpointResolver resolver = runId -> Optional.of(URI.create("https:artifacts.example.com"));
        PresignService service = serviceWithResolver(resolver);

        assertThrows(
                IllegalStateException.class,
                () -> service.generatePresignedUrl(UUID.randomUUID(), "runs/abc/file.txt", "GET", null));

        verify(artifactPresigners, never()).forEndpoint(any());
    }

    @Test
    void generatePresignedUrl_seamAnswersRootPathAndPort_isAccepted() throws Exception {
        URI endpoint = URI.create("https://artifacts.example.com:8443/");
        S3Presigner presigner = mock(S3Presigner.class);
        when(artifactPresigners.forEndpoint(endpoint)).thenReturn(presigner);
        stubPut(presigner, "https://artifacts.example.com:8443/choruskube/runs/abc/file.txt?sig=ddd");

        PresignService service = serviceWithResolver(runId -> Optional.of(endpoint));

        String url = service.generatePresignedUrl(UUID.randomUUID(), "runs/abc/file.txt", "PUT", 100L);

        assertEquals("https://artifacts.example.com:8443/choruskube/runs/abc/file.txt?sig=ddd", url);
        verify(artifactPresigners).forEndpoint(endpoint);
    }
}
