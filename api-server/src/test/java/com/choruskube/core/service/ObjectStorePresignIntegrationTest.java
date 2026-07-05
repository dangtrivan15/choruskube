package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.config.ObjectStoreConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Integration test exercising the real AWS SDK v2 presign + object path against a live MinIO
 * (S3-compatible) container.
 *
 * <p>Unit tests mock {@link S3Presigner}, so this is the only check that a real S3-compatible
 * backend actually accepts our presigned URLs. It deliberately builds the clients through the
 * production {@link ObjectStoreConfig} beans (fields set via reflection) so it validates the exact
 * wiring the app ships — path-style addressing, the signing region, and the checksum
 * configuration — rather than a hand-tuned client that could drift from production.
 *
 * <p>The two flows mirror how the agent uses object storage: a presigned PUT (the {@code artifact}
 * CLI uploading a result) and a presigned GET (the agent downloading an input artifact).
 */
@Testcontainers
class ObjectStorePresignIntegrationTest {

    private static final String ACCESS = "minioadmin";
    private static final String SECRET = "minioadmin";
    private static final String BUCKET = "choruskube-it";

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse("minio/minio:latest"))
            .withEnv("MINIO_ROOT_USER", ACCESS)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET)
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).withStartupTimeout(Duration.ofSeconds(60)));

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static S3Client s3;
    private static PresignService presignService;

    private static String endpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    @BeforeAll
    static void setUp() {
        // Build the clients exactly as production does — via the real @Configuration bean methods.
        ObjectStoreConfig config = new ObjectStoreConfig();
        ReflectionTestUtils.setField(config, "endpoint", endpoint());
        ReflectionTestUtils.setField(config, "accessKey", ACCESS);
        ReflectionTestUtils.setField(config, "secretKey", SECRET);
        ReflectionTestUtils.setField(config, "region", "us-east-1");

        s3 = config.s3Client();
        S3Presigner presigner = config.s3Presigner();
        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        presignService = new PresignService(presigner, BUCKET);
    }

    @Test
    void presignedPut_thenPresignedGet_roundTripsAgainstRealMinio() throws Exception {
        String key = "runs/it/out/hello.txt";
        byte[] body = "artifact-bytes-✓".getBytes(StandardCharsets.UTF_8);

        // Agent-style upload: PUT the bytes to a presigned PUT URL, as the `artifact` CLI does.
        String putUrl = presignService.generatePresignedUrl(key, "PUT");
        HttpResponse<String> put = HTTP.send(
                HttpRequest.newBuilder(URI.create(putUrl))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode())
                .as("presigned PUT accepted by MinIO (body: %s)", put.body())
                .isBetween(200, 299);

        // Agent-style download: GET the bytes back from a presigned GET URL.
        assertThat(getBytes(presignService.generatePresignedUrl(key, "GET"))).isEqualTo(body);
    }

    @Test
    void serverSideUpload_thenPresignedGet_roundTrips() throws Exception {
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

        assertThat(getBytes(presignService.generatePresignedUrl(key, "GET"))).isEqualTo(body);
    }

    private static byte[] getBytes(String url) throws Exception {
        HttpResponse<byte[]> get = HTTP.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(get.statusCode()).as("presigned GET status").isEqualTo(200);
        return get.body();
    }
}
