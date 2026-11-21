package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.choruskube.core.config.ObjectStoreConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

/**
 * Deletes against a real SeaweedFS 4.37, as an identity holding only List, Read, Tagging and Write
 * on the bucket — the least a deployment grants the api-server — so a store that wanted a separate
 * delete grant, or refused a batch delete, fails here rather than on the first retention pass.
 */
@Testcontainers
class ObjectTreeDeleterIntegrationTest {

    private static final String BUCKET = "choruskube-retention-it";
    private static final String ADMIN_ACCESS = "retention-admin";
    private static final String ADMIN_SECRET = "retention-admin-secret";
    private static final String API_ACCESS = "retention-api";
    private static final String API_SECRET = "retention-api-secret";

    private static final String IDENTITIES =
            """
            {"identities": [
              {"name": "admin", "credentials": [{"accessKey": "%s", "secretKey": "%s"}], "actions": ["Admin"]},
              {"name": "api", "credentials": [{"accessKey": "%s", "secretKey": "%s"}],
               "actions": ["List:%s", "Read:%s", "Tagging:%s", "Write:%s"]}
            ]}
            """.formatted(ADMIN_ACCESS, ADMIN_SECRET, API_ACCESS, API_SECRET, BUCKET, BUCKET, BUCKET, BUCKET);

    @Container
    static final GenericContainer<?> OBJECT_STORE = new GenericContainer<>(
                    DockerImageName.parse("chrislusf/seaweedfs:4.37"))
            .withCopyToContainer(Transferable.of(IDENTITIES), "/etc/seaweedfs/s3.json")
            .withCommand("server", "-dir=/data", "-s3", "-s3.port=9000", "-s3.config=/etc/seaweedfs/s3.json")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/healthz").forPort(9000).withStartupTimeout(Duration.ofSeconds(60)));

    private static S3Client api;
    private static ObjectTreeDeleter deleter;

    @BeforeAll
    static void setUp() {
        S3Client admin = client(ADMIN_ACCESS, ADMIN_SECRET);
        admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        api = client(API_ACCESS, API_SECRET);
        deleter = new ObjectTreeDeleter(api, BUCKET);
    }

    // Built through the real @Configuration bean method, so the checksum and addressing settings the
    // app ships are the ones a DeleteObjects request is sent with.
    private static S3Client client(String access, String secret) {
        ObjectStoreConfig config = new ObjectStoreConfig();
        ReflectionTestUtils.setField(
                config, "endpoint", "http://" + OBJECT_STORE.getHost() + ":" + OBJECT_STORE.getMappedPort(9000));
        ReflectionTestUtils.setField(config, "accessKey", access);
        ReflectionTestUtils.setField(config, "secretKey", secret);
        ReflectionTestUtils.setField(config, "region", "us-east-1");
        return config.s3Client();
    }

    @Test
    void deleteAll_emptiesTheTreeAcrossBatches_andLeavesItsNeighboursAlone() {
        String tree = "system/runs/run-a/";
        // Past one DeleteObjects request's 1000 keys, nested the way a run's outputs are.
        IntStream.range(0, 1005).forEach(i -> put(tree + "exec-" + (i % 7) + "/out/file-" + i + ".md"));
        put(tree + "run_log.md");
        put("system/runs/run-a-sibling/out/keep.md");
        put("system/runs/run-b/out/keep.md");

        deleter.deleteAll(tree);

        assertThat(deleter.isEmpty(tree)).isTrue();
        assertThat(deleter.isEmpty("system/runs/run-a-sibling/")).isFalse();
        assertThat(deleter.isEmpty("system/runs/run-b/")).isFalse();
    }

    @Test
    void theApiIdentity_isRefusedWhatItsGrantsDoNotCover() {
        // Without this, a store that ignored the identities file would pass every test above vacuously.
        assertThatThrownBy(() -> api.createBucket(
                        CreateBucketRequest.builder().bucket("not-granted").build()))
                .isInstanceOf(S3Exception.class)
                .satisfies(e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(403));
    }

    @Test
    void deleteAll_onAnEmptyTree_isANoOp() {
        deleter.deleteAll("system/runs/never-written/");

        assertThat(deleter.isEmpty("system/runs/never-written/")).isTrue();
    }

    @Test
    void refusesAPrefixThatIsNotATree() {
        for (String prefix : new String[] {null, "", " ", "/", "system/runs/run-a"}) {
            assertThatThrownBy(() -> deleter.deleteAll(prefix)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> deleter.isEmpty(prefix)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> deleter.deleteModifiedBefore(prefix, Instant.now()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void storagePrefixes_returnsEachTopLevelPrefixOnce_withATrailingSlash() {
        String org = "sp-" + UUID.randomUUID();
        put(org + "/staging/" + UUID.randomUUID() + "/a.txt");
        put(org + "/runs/" + UUID.randomUUID() + "/out/file.md");

        List<String> prefixes = deleter.storagePrefixes();

        assertThat(prefixes).contains(org + "/").allMatch(p -> p.endsWith("/"));
        assertThat(prefixes).doesNotHaveDuplicates();
    }

    @Test
    void deleteModifiedBefore_withACutoffInTheFuture_deletesOnlyUnderThatPrefix() {
        String org = "dmb-future-" + UUID.randomUUID();
        put(org + "/staging/" + UUID.randomUUID() + "/a.txt");
        put(org + "/staging/" + UUID.randomUUID() + "/b.txt");
        // Shares the "staging" text as a substring, not a path segment, and must survive.
        put(org + "-x/staging/" + UUID.randomUUID() + "/sibling.txt");
        put(org + "/runs/" + UUID.randomUUID() + "/inputs/kept.md");

        int deleted =
                deleter.deleteModifiedBefore(org + "/staging/", Instant.now().plusSeconds(60));

        assertThat(deleted).isEqualTo(2);
        assertThat(deleter.isEmpty(org + "/staging/")).isTrue();
        assertThat(deleter.isEmpty(org + "-x/staging/")).isFalse();
        assertThat(deleter.isEmpty(org + "/runs/")).isFalse();
    }

    @Test
    void deleteModifiedBefore_withACutoffInThePast_deletesNothing() {
        String org = "dmb-past-" + UUID.randomUUID();
        put(org + "/staging/" + UUID.randomUUID() + "/a.txt");

        int deleted =
                deleter.deleteModifiedBefore(org + "/staging/", Instant.now().minusSeconds(60));

        assertThat(deleted).isZero();
        assertThat(deleter.isEmpty(org + "/staging/")).isFalse();
    }

    @Test
    void deleteModifiedBefore_deletesMoreThanOneBatch() {
        String org = "dmb-batch-" + UUID.randomUUID();
        IntStream.range(0, 1005).forEach(i -> put(org + "/staging/s/file-" + i + ".txt"));

        int deleted =
                deleter.deleteModifiedBefore(org + "/staging/", Instant.now().plusSeconds(60));

        assertThat(deleted).isEqualTo(1005);
        assertThat(deleter.isEmpty(org + "/staging/")).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteAll_throwsWhenTheStoreRefusesAKey() {
        S3Client store = mock(S3Client.class);
        ListObjectsV2Iterable pages = mock(ListObjectsV2Iterable.class);
        SdkIterable<S3Object> contents =
                () -> List.of(S3Object.builder().key("system/runs/r/x").build()).iterator();
        when(pages.contents()).thenReturn(contents);
        when(store.listObjectsV2Paginator(any(Consumer.class))).thenReturn(pages);
        when(store.deleteObjects(any(Consumer.class)))
                .thenReturn(DeleteObjectsResponse.builder()
                        .errors(S3Error.builder()
                                .key("system/runs/r/x")
                                .code("AccessDenied")
                                .build())
                        .build());

        assertThatThrownBy(() -> new ObjectTreeDeleter(store, BUCKET).deleteAll("system/runs/r/"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AccessDenied");
    }

    private static void put(String key) {
        api.putObject(PutObjectRequest.builder().bucket(BUCKET).key(key).build(), RequestBody.fromString(key));
    }
}
