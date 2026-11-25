package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.choruskube.core.config.ArtifactPresigners;
import com.choruskube.core.config.ObjectStoreConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Proves against {@link PinnedObjectStore#IMAGE}, cross-run and with production's bucket-scoped
 * identity shape, that a URL from the real {@link PresignService} acts only on the object it was
 * signed for: an unsigned control header, a forwarding-prefix retarget or an added query parameter
 * is refused, and the same URL sent unmodified succeeds. No Spring context: the service and the
 * run-storage-scope check are built directly, the way {@link ObjectStorePresignIntegrationTest}
 * already builds a {@link PresignService} against this same container image.
 */
@Testcontainers
class PresignedRequestStoreContractTest {

    private static final String BUCKET = "choruskube-contract-it";
    private static final String ADMIN_ACCESS = "contract-admin";
    private static final String ADMIN_SECRET = "contract-admin-secret";
    private static final String SCOPED_ACCESS = "contract-scoped";
    private static final String SCOPED_SECRET = "contract-scoped-secret";
    private static final String S3_CONFIG_PATH = "/etc/seaweedfs/s3.json";

    // Production's identity shape: an admin identity used only by this test's setup and
    // verification, plus one identity scoped to exactly List/Read/Tagging/Write on the bucket --
    // the only grant PresignService ever signs for.
    private static String identitiesConfig() {
        return """
                {
                  "identities": [
                    {
                      "name": "admin",
                      "credentials": [{"accessKey": "%s", "secretKey": "%s"}],
                      "actions": ["Admin", "Read", "List", "Tagging", "Write"]
                    },
                    {
                      "name": "contract-it",
                      "credentials": [{"accessKey": "%s", "secretKey": "%s"}],
                      "actions": ["List:%s", "Read:%s", "Tagging:%s", "Write:%s"]
                    }
                  ]
                }
                """
                .formatted(
                        ADMIN_ACCESS, ADMIN_SECRET, SCOPED_ACCESS, SCOPED_SECRET, BUCKET, BUCKET, BUCKET, BUCKET);
    }

    @Container
    static final GenericContainer<?> OBJECT_STORE = new GenericContainer<>(PinnedObjectStore.IMAGE)
            .withCopyToContainer(Transferable.of(identitiesConfig()), S3_CONFIG_PATH)
            .withCommand("server", "-dir=/data", "-s3", "-s3.port=9000", "-s3.config=" + S3_CONFIG_PATH)
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/healthz").forPort(9000).withStartupTimeout(Duration.ofSeconds(60)));

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static S3Client adminClient;
    private static PresignService presignService;
    private static RunStorageScope runStorageScope;

    @BeforeAll
    static void setUp() {
        String endpoint = "http://" + OBJECT_STORE.getHost() + ":" + OBJECT_STORE.getMappedPort(9000);

        ObjectStoreConfig adminConfig = new ObjectStoreConfig();
        ReflectionTestUtils.setField(adminConfig, "endpoint", endpoint);
        ReflectionTestUtils.setField(adminConfig, "accessKey", ADMIN_ACCESS);
        ReflectionTestUtils.setField(adminConfig, "secretKey", ADMIN_SECRET);
        ReflectionTestUtils.setField(adminConfig, "region", "us-east-1");
        adminClient = adminConfig.s3Client();
        adminClient.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());

        ObjectStoreConfig scopedConfig = new ObjectStoreConfig();
        ReflectionTestUtils.setField(scopedConfig, "endpoint", endpoint);
        ReflectionTestUtils.setField(scopedConfig, "accessKey", SCOPED_ACCESS);
        ReflectionTestUtils.setField(scopedConfig, "secretKey", SCOPED_SECRET);
        ReflectionTestUtils.setField(scopedConfig, "region", "us-east-1");
        ArtifactPresigners artifactPresigners = scopedConfig.artifactPresigners();

        @SuppressWarnings("unchecked")
        ObjectProvider<ArtifactSigningEndpointResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any()))
                .thenAnswer(inv -> ((Supplier<ArtifactSigningEndpointResolver>) inv.getArgument(0)).get());
        presignService = new PresignService(artifactPresigners, provider, BUCKET);

        runStorageScope = new RunStorageScope(new SystemStoragePrefixResolver(), new ObjectMapper());
    }

    /** Two runs, with run B's run log seeded by the admin identity -- the object run A must never reach. */
    private record TwoRuns(UUID runA, UUID runB, String treeA, String treeB, String bKey, String bContent) {}

    private TwoRuns twoRuns() {
        UUID runA = UUID.randomUUID();
        UUID runB = UUID.randomUUID();
        String treeA = runStorageScope.treeOf(runA);
        String treeB = runStorageScope.treeOf(runB);
        String bKey = treeB + "out/run_log.md";
        String bContent = "run log for " + runB;
        putAdmin(bKey, bContent);
        return new TwoRuns(runA, runB, treeA, treeB, bKey, bContent);
    }

    private static void putAdmin(String key, String content) {
        adminClient.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key(key).build(),
                RequestBody.fromString(content, StandardCharsets.UTF_8));
    }

    private static String getAdmin(String key) {
        return adminClient
                .getObjectAsBytes(GetObjectRequest.builder().bucket(BUCKET).key(key).build())
                .asUtf8String();
    }

    private static boolean existsAdmin(String key) {
        try {
            adminClient.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    private static HttpResponse<byte[]> send(URI target, String method, byte[] body, Map<String, String> headers)
            throws Exception {
        HttpRequest.BodyPublisher publisher =
                body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target).method(method, publisher);
        headers.forEach(builder::header);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** {@code signedUrl} with its path and query replaced, same scheme and authority. */
    private static URI retarget(String signedUrl, String path, String query) {
        URI signed = URI.create(signedUrl);
        return URI.create(signed.getScheme() + "://" + signed.getAuthority() + path + "?" + query);
    }

    // --- PUT carrying an unsigned copy-source that names another run's key, in every spelling ---

    private static Stream<Arguments> copySourceSpellings() {
        Function<String, String> leadingSlash = key -> "/" + BUCKET + "/" + key;
        Function<String, String> noSlash = key -> BUCKET + "/" + key;
        Function<String, String> percentEncoded = key -> ("/" + BUCKET + "/" + key).replace("/", "%2F");
        return Stream.of(
                Arguments.of("leading slash", "x-amz-copy-source", leadingSlash),
                Arguments.of("no slash", "x-amz-copy-source", noSlash),
                Arguments.of("percent-encoded", "x-amz-copy-source", percentEncoded),
                Arguments.of("upper-case header name", "X-Amz-Copy-Source", leadingSlash));
    }

    @ParameterizedTest(name = "unsigned copy-source refused: {0}")
    @MethodSource("copySourceSpellings")
    void unsignedCopySource_refused_targetUntouched(
            String description, String headerName, Function<String, String> valueFn) throws Exception {
        TwoRuns t = twoRuns();
        String destKey = t.treeA() + "copy-dest-" + UUID.randomUUID() + ".txt";
        String putUrl = presignService.generatePresignedUrl(t.runA(), destKey, "PUT", 0L);

        HttpResponse<byte[]> response =
                send(URI.create(putUrl), "PUT", new byte[0], Map.of(headerName, valueFn.apply(t.bKey())));

        assertThat(response.statusCode())
                .as("%s (body: %s)", description, new String(response.body(), StandardCharsets.UTF_8))
                .isGreaterThanOrEqualTo(400);
        if (existsAdmin(destKey)) {
            assertThat(getAdmin(destKey))
                    .as("copy destination must not hold the source's bytes")
                    .isNotEqualTo(t.bContent());
        }
    }

    // --- PUT carrying any other unsigned x-amz-* control header ---

    private static Stream<Arguments> otherControlHeaders() {
        return Stream.of(
                Arguments.of("metadata-directive", "x-amz-metadata-directive", "REPLACE"),
                Arguments.of("tagging", "x-amz-tagging", "k=v"),
                Arguments.of("acl", "x-amz-acl", "public-read"),
                Arguments.of("user-metadata", "x-amz-meta-contract-it", "probe"),
                Arguments.of("made-up x-amz-* header", "x-amz-contract-it-made-up", "probe"));
    }

    @ParameterizedTest(name = "unsigned control header refused: {0}")
    @MethodSource("otherControlHeaders")
    void unsignedControlHeader_refused(String description, String headerName, String headerValue) throws Exception {
        TwoRuns t = twoRuns();
        String destKey = t.treeA() + "control-header-" + UUID.randomUUID() + ".txt";
        byte[] body = "control-header-probe".getBytes(StandardCharsets.UTF_8);
        String putUrl = presignService.generatePresignedUrl(t.runA(), destKey, "PUT", (long) body.length);

        HttpResponse<byte[]> response = send(URI.create(putUrl), "PUT", body, Map.of(headerName, headerValue));

        assertThat(response.statusCode()).as(description).isGreaterThanOrEqualTo(400);
    }

    // --- forwarding-prefix retarget to another run's key, as a GET and as a length-signed PUT ---

    /**
     * A key in A's own tree whose tail spells {@code targetPath}, asserted to pass the run-scope
     * check the presign endpoint applies, so the request below is one an agent can really obtain.
     */
    private String keyInTreeASpelling(TwoRuns t, String targetPath) {
        String key = t.treeA() + "x" + targetPath;
        assertThat(runStorageScope.contains(t.runA(), key))
                .as("presign admits %s for run A", key)
                .isTrue();
        return key;
    }

    /** The forwarding prefix that, joined to {@code targetPath}, rebuilds {@link #keyInTreeASpelling}'s path. */
    private static String prefixRebuildingSignedPath(TwoRuns t) {
        return "/" + BUCKET + "/" + t.treeA() + "x";
    }

    @Test
    void retargetToAnotherRunsKey_get_refused_targetUnchanged() throws Exception {
        TwoRuns t = twoRuns();
        String actualPath = "/" + BUCKET + "/" + t.bKey();
        String getUrl = presignService.generatePresignedUrl(t.runA(), keyInTreeASpelling(t, actualPath), "GET", null);

        HttpResponse<byte[]> response = send(
                retarget(getUrl, actualPath, URI.create(getUrl).getRawQuery()),
                "GET",
                null,
                Map.of("X-Forwarded-Prefix", prefixRebuildingSignedPath(t)));

        assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).doesNotContain(t.bContent());
        assertThat(getAdmin(t.bKey())).isEqualTo(t.bContent());
    }

    @Test
    void retargetToAnotherRunsKey_lengthSignedPut_refused_targetUnchanged() throws Exception {
        TwoRuns t = twoRuns();
        String actualPath = "/" + BUCKET + "/" + t.bKey();
        byte[] attackerBody = "overwritten-from-run-a".getBytes(StandardCharsets.UTF_8);
        String putUrl = presignService.generatePresignedUrl(
                t.runA(), keyInTreeASpelling(t, actualPath), "PUT", (long) attackerBody.length);

        HttpResponse<byte[]> response = send(
                retarget(putUrl, actualPath, URI.create(putUrl).getRawQuery()),
                "PUT",
                attackerBody,
                Map.of("X-Forwarded-Prefix", prefixRebuildingSignedPath(t)));

        assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(getAdmin(t.bKey())).isEqualTo(t.bContent());
    }

    // --- forwarding-prefix retarget to the bucket's root: no listing returned ---

    @Test
    void retargetToBucketRoot_refused_noListingReturned() throws Exception {
        TwoRuns t = twoRuns();
        String actualPath = "/" + BUCKET;
        String getUrl = presignService.generatePresignedUrl(t.runA(), keyInTreeASpelling(t, actualPath), "GET", null);

        HttpResponse<byte[]> response = send(
                retarget(getUrl, actualPath, URI.create(getUrl).getRawQuery()),
                "GET",
                null,
                Map.of("X-Forwarded-Prefix", prefixRebuildingSignedPath(t)));

        assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).doesNotContain(t.bKey());
    }

    // --- a URL with an added query parameter (a subresource or an upload id) is refused ---

    private static Stream<Arguments> addedQueryParameters() {
        Function<String, String> tagging = key -> "tagging=";
        Function<String, String> acl = key -> "acl=";
        // A live upload's id: a made-up one is refused as NoSuchUpload even by a store that left
        // the added parameter out of the signature, so the row would pass vacuously.
        Function<String, String> liveUploadId = key -> "uploadId="
                + URLEncoder.encode(
                        adminClient
                                .createMultipartUpload(CreateMultipartUploadRequest.builder()
                                        .bucket(BUCKET)
                                        .key(key)
                                        .build())
                                .uploadId(),
                        StandardCharsets.UTF_8);
        return Stream.of(
                Arguments.of("tagging subresource", tagging),
                Arguments.of("acl subresource", acl),
                Arguments.of("upload id", liveUploadId));
    }

    @ParameterizedTest(name = "added query parameter refused: {0}")
    @MethodSource("addedQueryParameters")
    void addedQueryParameter_refused(String description, Function<String, String> extraParamFor) throws Exception {
        TwoRuns t = twoRuns();
        String key = t.treeA() + "query-param-" + UUID.randomUUID() + ".txt";
        putAdmin(key, "query-param-probe");
        String getUrl = presignService.generatePresignedUrl(t.runA(), key, "GET", null);

        HttpResponse<byte[]> response = send(
                retarget(
                        getUrl,
                        URI.create(getUrl).getRawPath(),
                        URI.create(getUrl).getRawQuery() + "&" + extraParamFor.apply(key)),
                "GET",
                null,
                Map.of());

        assertThat(response.statusCode())
                .as("%s (body: %s)", description, new String(response.body(), StandardCharsets.UTF_8))
                .isGreaterThanOrEqualTo(400);
    }

    // --- the unmodified URLs still succeed, so the refusals above are not vacuous ---

    @Test
    void unmodifiedUrls_succeed() throws Exception {
        TwoRuns t = twoRuns();
        String key = t.treeA() + "control-" + UUID.randomUUID() + ".txt";
        byte[] body = "control-body".getBytes(StandardCharsets.UTF_8);

        String putUrl = presignService.generatePresignedUrl(t.runA(), key, "PUT", (long) body.length);
        HttpResponse<byte[]> put = send(URI.create(putUrl), "PUT", body, Map.of());
        assertThat(put.statusCode())
                .as("control PUT (body: %s)", new String(put.body(), StandardCharsets.UTF_8))
                .isBetween(200, 299);

        String getUrl = presignService.generatePresignedUrl(t.runA(), key, "GET", null);
        HttpResponse<byte[]> get = send(URI.create(getUrl), "GET", null, Map.of());
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.body()).isEqualTo(body);
    }

    @Test
    void unmodifiedZeroLengthPut_succeeds() throws Exception {
        // The copy-source rows send this request plus one header, so they are vacuous unless it succeeds.
        TwoRuns t = twoRuns();
        String key = t.treeA() + "empty-" + UUID.randomUUID() + ".txt";
        String putUrl = presignService.generatePresignedUrl(t.runA(), key, "PUT", 0L);

        HttpResponse<byte[]> put = send(URI.create(putUrl), "PUT", new byte[0], Map.of());

        assertThat(put.statusCode())
                .as("empty PUT (body: %s)", new String(put.body(), StandardCharsets.UTF_8))
                .isBetween(200, 299);
        assertThat(existsAdmin(key)).isTrue();
    }

    @Test
    void payloadHashHeaderOnly_stillSucceeds() throws Exception {
        TwoRuns t = twoRuns();
        String key = t.treeA() + "payload-hash-" + UUID.randomUUID() + ".txt";
        byte[] body = "payload-hash-body".getBytes(StandardCharsets.UTF_8);
        String putUrl = presignService.generatePresignedUrl(t.runA(), key, "PUT", (long) body.length);

        HttpResponse<byte[]> response =
                send(URI.create(putUrl), "PUT", body, Map.of("x-amz-content-sha256", "UNSIGNED-PAYLOAD"));

        assertThat(response.statusCode())
                .as("body: %s", new String(response.body(), StandardCharsets.UTF_8))
                .isBetween(200, 299);
        assertThat(getAdmin(key)).isEqualTo(new String(body, StandardCharsets.UTF_8));
    }
}
