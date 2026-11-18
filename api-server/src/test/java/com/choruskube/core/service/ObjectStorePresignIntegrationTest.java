package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.choruskube.core.config.ArtifactPresigners;
import com.choruskube.core.config.ObjectStoreConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Integration test exercising the real AWS SDK v2 presign + object path against a live SeaweedFS
 * (S3-compatible) container.
 *
 * <p>Unit tests mock {@link S3Presigner}, so this is the only check that a real S3-compatible
 * backend actually accepts our presigned URLs, actually checks the Host it received against what
 * was signed, and that signing for a second, deployment-named endpoint produces a URL the same
 * container still accepts. It deliberately builds the clients through the production {@link
 * ObjectStoreConfig} beans (fields set via reflection) so it validates the exact wiring the app
 * ships — path-style addressing, the signing region, and the checksum configuration — rather than
 * a hand-tuned client that could drift from production.
 */
@Testcontainers
class ObjectStorePresignIntegrationTest {

    private static final String ACCESS = "choruskube-it";
    private static final String SECRET = "choruskube-it-secret";
    private static final String BUCKET = "choruskube-it";

    @Container
    static final GenericContainer<?> OBJECT_STORE = new GenericContainer<>(
                    DockerImageName.parse("chrislusf/seaweedfs:4.37"))
            // The S3 gateway's admin identity; without one it would accept unsigned requests and prove
            // nothing about the signatures this test exists to check.
            .withEnv("AWS_ACCESS_KEY_ID", ACCESS)
            .withEnv("AWS_SECRET_ACCESS_KEY", SECRET)
            .withCommand("server", "-dir=/data", "-s3", "-s3.port=9000")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/healthz").forPort(9000).withStartupTimeout(Duration.ofSeconds(60)));

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static S3Client s3;
    private static ArtifactPresigners artifactPresigners;
    private static URI ownEndpoint;

    // A second, independent authority reaching the exact same container as ownEndpoint (same
    // mapped port, different host spelling) — proves presigning for a named endpoint signs for
    // *that* authority specifically, not incidentally whatever the server's own endpoint is.
    private static URI secondAuthorityEndpoint;

    @BeforeAll
    static void setUp() {
        ownEndpoint = URI.create("http://" + OBJECT_STORE.getHost() + ":" + OBJECT_STORE.getMappedPort(9000));
        secondAuthorityEndpoint = URI.create("http://127.0.0.1:" + OBJECT_STORE.getMappedPort(9000));

        // Build the clients exactly as production does — via the real @Configuration bean methods.
        ObjectStoreConfig config = new ObjectStoreConfig();
        ReflectionTestUtils.setField(config, "endpoint", ownEndpoint.toString());
        ReflectionTestUtils.setField(config, "accessKey", ACCESS);
        ReflectionTestUtils.setField(config, "secretKey", SECRET);
        ReflectionTestUtils.setField(config, "region", "us-east-1");

        s3 = config.s3Client();
        artifactPresigners = config.artifactPresigners();
        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    private static PresignService presignServiceWithResolver(ArtifactSigningEndpointResolver resolver) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ArtifactSigningEndpointResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any()))
                .thenAnswer(inv -> resolver != null
                        ? resolver
                        : ((Supplier<ArtifactSigningEndpointResolver>) inv.getArgument(0)).get());
        return new PresignService(artifactPresigners, provider, BUCKET);
    }

    @Test
    void presignedPut_thenPresignedGet_roundTripsAgainstARealObjectStore() throws Exception {
        PresignService presignService = presignServiceWithResolver(null);
        String key = "runs/it/out/hello.txt";
        byte[] body = "artifact-bytes-✓".getBytes(StandardCharsets.UTF_8);

        // Agent-style upload: PUT the bytes to a presigned PUT URL, as the `artifact` CLI does.
        String putUrl = presignService.generatePresignedUrl(UUID.randomUUID(), key, "PUT", (long) body.length);
        HttpResponse<String> put = HTTP.send(
                HttpRequest.newBuilder(URI.create(putUrl))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode())
                .as("presigned PUT accepted by the object store (body: %s)", put.body())
                .isBetween(200, 299);

        // Agent-style download: GET the bytes back from a presigned GET URL.
        assertThat(getBytes(presignService.generatePresignedUrl(UUID.randomUUID(), key, "GET", null)))
                .isEqualTo(body);
    }

    @Test
    void serverSideUpload_thenPresignedGet_roundTrips() throws Exception {
        PresignService presignService = presignServiceWithResolver(null);
        // The real input-artifact flow: the server puts the object (this is the exact
        // S3Client.putObject call shape UploadService uses), the agent fetches it via presigned GET.
        String key = "runs/it/inputs/design.png";
        byte[] body = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(BUCKET)
                        .key(key)
                        .contentType("image/png")
                        .build(),
                RequestBody.fromBytes(body));

        assertThat(getBytes(presignService.generatePresignedUrl(UUID.randomUUID(), key, "GET", null)))
                .isEqualTo(body);
    }

    @Test
    void seamNamingASecondAuthority_signsForThatAuthority_pathStyle() {
        PresignService presignService = presignServiceWithResolver(runId -> Optional.of(secondAuthorityEndpoint));

        String url = presignService.generatePresignedUrl(UUID.randomUUID(), "runs/it/second-authority.txt", "PUT", 1L);

        URI parsed = URI.create(url);
        assertThat(parsed.getHost()).isEqualTo("127.0.0.1");
        assertThat(parsed.getPort()).isEqualTo(OBJECT_STORE.getMappedPort(9000));
        assertThat(parsed.getPath()).isEqualTo("/" + BUCKET + "/runs/it/second-authority.txt");
    }

    @Test
    void seamNamingASecondAuthority_putThenGet_roundTripsThroughThatAuthority() throws Exception {
        UUID runId = UUID.randomUUID();
        PresignService presignService = presignServiceWithResolver(id -> Optional.of(secondAuthorityEndpoint));
        String key = "runs/it/second-authority-roundtrip.txt";
        byte[] body = "second-authority-bytes".getBytes(StandardCharsets.UTF_8);

        String putUrl = presignService.generatePresignedUrl(runId, key, "PUT", (long) body.length);
        HttpResponse<String> put = HTTP.send(
                HttpRequest.newBuilder(URI.create(putUrl))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode())
                .as("presigned PUT (second authority) accepted by the object store (body: %s)", put.body())
                .isBetween(200, 299);

        assertThat(getBytes(presignService.generatePresignedUrl(runId, key, "GET", null)))
                .isEqualTo(body);
    }

    @Test
    void getSignedUrl_usedForPut_isRefusedByTheStore_andViceVersa() throws Exception {
        PresignService presignService = presignServiceWithResolver(null);
        String key = "runs/it/method-mismatch.txt";
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET).key(key).build(), RequestBody.fromBytes(new byte[] {1}));

        String getUrl = presignService.generatePresignedUrl(UUID.randomUUID(), key, "GET", null);
        HttpResponse<String> putWithGetUrl = HTTP.send(
                HttpRequest.newBuilder(URI.create(getUrl))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[] {2}))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(putWithGetUrl.statusCode())
                .as("PUT against a GET-signed URL")
                .isGreaterThanOrEqualTo(400);

        String putUrl = presignService.generatePresignedUrl(UUID.randomUUID(), key + "-other", "PUT", 1L);
        HttpResponse<String> getWithPutUrl = HTTP.send(
                HttpRequest.newBuilder(URI.create(putUrl)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(getWithPutUrl.statusCode())
                .as("GET against a PUT-signed URL")
                .isGreaterThanOrEqualTo(400);
    }

    @Test
    void urlSignedForOneAuthority_sentDirectlyToAnother_isRefused() throws Exception {
        PresignService presignService = presignServiceWithResolver(runId -> Optional.of(ownEndpoint));
        String key = "runs/it/host-bound.txt";

        URI signedForOwnEndpoint = URI.create(presignService.generatePresignedUrl(UUID.randomUUID(), key, "PUT", 1L));

        // Same path and query (same signature), dialled directly against the *other* authority's
        // socket — both authorities reach the same container, so only the Host sent differs from
        // what was signed.
        URI redirectedToSecondAuthority = new URI(
                secondAuthorityEndpoint.getScheme(),
                null,
                secondAuthorityEndpoint.getHost(),
                secondAuthorityEndpoint.getPort(),
                signedForOwnEndpoint.getPath(),
                signedForOwnEndpoint.getQuery(),
                null);

        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(redirectedToSecondAuthority)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[] {3}))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("same signature sent to a different authority (body: %s)", response.body())
                .isGreaterThanOrEqualTo(400);
    }

    @Test
    void urlSignedForNBytes_acceptsExactlyN_refusesOffByOneAndEmpty() throws Exception {
        PresignService presignService = presignServiceWithResolver(null);
        int n = 10;

        assertThat(putStatus(presignService, "runs/it/sized-exact.txt", n, new byte[n]))
                .as("PUT at exactly the signed length")
                .isBetween(200, 299);
        assertThat(putStatus(presignService, "runs/it/sized-short.txt", n, new byte[n - 1]))
                .as("PUT one byte short of the signed length")
                .isGreaterThanOrEqualTo(400);
        assertThat(putStatus(presignService, "runs/it/sized-long.txt", n, new byte[n + 1]))
                .as("PUT one byte over the signed length")
                .isGreaterThanOrEqualTo(400);
        assertThat(putStatus(presignService, "runs/it/sized-empty.txt", n, new byte[0]))
                .as("PUT an empty body against a URL signed for a non-zero length")
                .isGreaterThanOrEqualTo(400);
    }

    @Test
    void urlSignedForZeroBytes_acceptsEmptyBody_refusesOneByteBody() throws Exception {
        PresignService presignService = presignServiceWithResolver(null);

        assertThat(putStatus(presignService, "runs/it/sized-zero-empty.txt", 0, new byte[0]))
                .as("PUT an empty body against a URL signed for zero bytes")
                .isBetween(200, 299);
        assertThat(putStatus(presignService, "runs/it/sized-zero-nonempty.txt", 0, new byte[1]))
                .as("PUT a one-byte body against a URL signed for zero bytes")
                .isGreaterThanOrEqualTo(400);
    }

    @Test
    void sameKey_differentContentLength_yieldsADifferentUrl() {
        PresignService presignService = presignServiceWithResolver(null);
        UUID runId = UUID.randomUUID();
        String key = "runs/it/sized-cache-key.txt";

        String url10 = presignService.generatePresignedUrl(runId, key, "PUT", 10L);
        String url20 = presignService.generatePresignedUrl(runId, key, "PUT", 20L);

        assertThat(url10).isNotEqualTo(url20);
    }

    private static int putStatus(PresignService presignService, String key, long signedLength, byte[] body)
            throws Exception {
        String putUrl = presignService.generatePresignedUrl(UUID.randomUUID(), key, "PUT", signedLength);
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(putUrl))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    @Test
    void defaultEndpoint_producesTheSameShapeUrl_asThePreChangeBean() throws Exception {
        S3Presigner rawPresigner = S3Presigner.builder()
                .endpointOverride(ownEndpoint)
                .region(Region.of("us-east-1"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS, SECRET)))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        try {
            String key = "runs/it/shape-check.txt";

            PresignService presignService = presignServiceWithResolver(null);
            URI viaService = URI.create(presignService.generatePresignedUrl(UUID.randomUUID(), key, "GET", null));
            URI viaRawPresigner = URI.create(rawPresigner
                    .presignGetObject(GetObjectPresignRequest.builder()
                            .signatureDuration(Duration.ofSeconds(900))
                            .getObjectRequest(GetObjectRequest.builder()
                                    .bucket(BUCKET)
                                    .key(key)
                                    .build())
                            .build())
                    .url()
                    .toString());

            assertThat(viaService.getScheme()).isEqualTo(viaRawPresigner.getScheme());
            assertThat(viaService.getHost()).isEqualTo(viaRawPresigner.getHost());
            assertThat(viaService.getPort()).isEqualTo(viaRawPresigner.getPort());
            assertThat(viaService.getPath()).isEqualTo(viaRawPresigner.getPath());
            assertThat(queryParamsExcludingSigningTime(viaService))
                    .isEqualTo(queryParamsExcludingSigningTime(viaRawPresigner));
        } finally {
            rawPresigner.close();
        }
    }

    private static Map<String, String> queryParamsExcludingSigningTime(URI uri) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : uri.getQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals("X-Amz-Date") || kv[0].equals("X-Amz-Signature")) {
                continue;
            }
            params.put(kv[0], kv.length > 1 ? kv[1] : "");
        }
        return params;
    }

    private static byte[] getBytes(String url) throws Exception {
        HttpResponse<byte[]> get = HTTP.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(get.statusCode()).as("presigned GET status").isEqualTo(200);
        return get.body();
    }
}
