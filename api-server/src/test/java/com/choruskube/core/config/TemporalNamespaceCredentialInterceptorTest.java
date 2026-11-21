package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.credential.ControlPlaneTemporalTokens;
import com.choruskube.core.credential.TemporalBearerToken;
import com.choruskube.core.credential.TemporalTokenSupplier;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.temporal.api.enums.v1.TaskQueueType;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueResponse;
import io.temporal.api.workflowservice.v1.GetSystemInfoRequest;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link TemporalNamespaceCredentialInterceptor} at the gRPC boundary: a fake {@link
 * WorkflowServiceGrpc.WorkflowServiceImplBase} behind an in-process channel records each call's
 * {@code authorization} metadata, and {@link WorkflowServiceStubs} are built the same way {@link
 * TemporalConfig#workflowServiceStubs} builds them, minus the real network target.
 */
class TemporalNamespaceCredentialInterceptorTest {

    private static final Metadata.Key<String> AUTH_HEADER =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Context.Key<String> AUTH_CONTEXT = Context.key("recorded-auth");

    private Server server;
    private ManagedChannel channel;
    private WorkflowServiceStubs stubs;

    @AfterEach
    void tearDown() {
        if (stubs != null) {
            stubs.shutdownNow();
        }
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    private RecordingService start(ControlPlaneTemporalTokens tokens) throws Exception {
        RecordingService service = new RecordingService();
        String name = "temporal-cred-" + System.nanoTime();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(ServerInterceptors.intercept(service, authRecordingInterceptor()))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        WorkflowServiceStubsOptions.Builder builder =
                TemporalConfig.stubsOptions(null, tokens).setChannel(channel).setDisableHealthCheck(true);
        stubs = WorkflowServiceStubs.newServiceStubs(builder.build());
        return service;
    }

    private static ServerInterceptor authRecordingInterceptor() {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                Context ctx = Context.current().withValue(AUTH_CONTEXT, headers.get(AUTH_HEADER));
                return Contexts.interceptCall(ctx, call, headers, next);
            }
        };
    }

    private static ControlPlaneTemporalTokens tokensFor(Map<String, String> byNamespace) {
        TemporalTokenSupplier supplier = ns -> {
            String value = byNamespace.get(ns);
            return value == null
                    ? Optional.empty()
                    : Optional.of(new TemporalBearerToken(value, Instant.now().plusSeconds(600)));
        };
        return new ControlPlaneTemporalTokens(
                Optional.of(supplier), Clock.systemUTC(), Executors.newCachedThreadPool());
    }

    private static ControlPlaneTemporalTokens noSource() {
        return new ControlPlaneTemporalTokens(Optional.empty(), Clock.systemUTC(), Executors.newCachedThreadPool());
    }

    @Test
    void startSignalTerminateAndDescribeTaskQueue_eachCarryTheirNamespacesToken() throws Exception {
        RecordingService service = start(tokensFor(Map.of("ns-a", "tok-a", "ns-b", "tok-b")));

        stubs.blockingStub()
                .startWorkflowExecution(StartWorkflowExecutionRequest.newBuilder()
                        .setNamespace("ns-a")
                        .setWorkflowId("wf")
                        .build());
        stubs.blockingStub()
                .signalWorkflowExecution(SignalWorkflowExecutionRequest.newBuilder()
                        .setNamespace("ns-a")
                        .build());
        stubs.blockingStub()
                .terminateWorkflowExecution(TerminateWorkflowExecutionRequest.newBuilder()
                        .setNamespace("ns-a")
                        .build());
        stubs.blockingStub()
                .describeTaskQueue(DescribeTaskQueueRequest.newBuilder()
                        .setNamespace("ns-a")
                        .setTaskQueue(TaskQueue.newBuilder().setName("q"))
                        .setTaskQueueType(TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW)
                        .build());

        assertThat(service.authFor("StartWorkflowExecution")).isEqualTo("Bearer tok-a");
        assertThat(service.authFor("SignalWorkflowExecution")).isEqualTo("Bearer tok-a");
        assertThat(service.authFor("TerminateWorkflowExecution")).isEqualTo("Bearer tok-a");
        assertThat(service.authFor("DescribeTaskQueue")).isEqualTo("Bearer tok-a");
    }

    /** The path the api-server's own callers take: namespace-bound clients over the one shared stubs. */
    @Test
    void workflowClientCallsThroughTheRegistry_eachCarryTheirNamespacesToken() throws Exception {
        RecordingService service = start(tokensFor(Map.of("ns-a", "tok-a", "ns-b", "tok-b")));
        WorkflowClient defaultClient = WorkflowClient.newInstance(
                stubs, WorkflowClientOptions.newBuilder().setNamespace("ns-a").build());
        WorkflowClientRegistry registry = new WorkflowClientRegistry(stubs, defaultClient, "ns-a");

        for (String ns : List.of("ns-a", "ns-b")) {
            WorkflowClient client = registry.clientFor(ns);
            client.newUntypedWorkflowStub(
                            "AnyWorkflow",
                            WorkflowOptions.newBuilder()
                                    .setWorkflowId("wf-" + ns)
                                    .setTaskQueue("q")
                                    .build())
                    .start();
            WorkflowStub existing = client.newUntypedWorkflowStub("wf-" + ns);
            existing.signal("any-signal");
            existing.terminate("deleted");
        }

        assertThat(service.authsFor("StartWorkflowExecution")).containsExactly("Bearer tok-a", "Bearer tok-b");
        assertThat(service.authsFor("SignalWorkflowExecution")).containsExactly("Bearer tok-a", "Bearer tok-b");
        assertThat(service.authsFor("TerminateWorkflowExecution")).containsExactly("Bearer tok-a", "Bearer tok-b");
    }

    @Test
    void blankNamespace_carriesNoHeader_evenWhenTheSourceWouldAnswerForIt() throws Exception {
        TemporalTokenSupplier everyNamespace = ns ->
                Optional.of(new TemporalBearerToken("tok-" + ns, Instant.now().plusSeconds(600)));
        RecordingService service = start(new ControlPlaneTemporalTokens(
                Optional.of(everyNamespace), Clock.systemUTC(), Executors.newCachedThreadPool()));

        stubs.blockingStub()
                .signalWorkflowExecution(SignalWorkflowExecutionRequest.newBuilder()
                        .setNamespace("")
                        .build());

        assertThat(service.requestCountFor("SignalWorkflowExecution")).isEqualTo(1);
        assertThat(service.authFor("SignalWorkflowExecution")).isNull();
    }

    @Test
    void hangingSource_callFailsUnauthenticatedWithinItsDeadline_andNeverReachesTheServer() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        TemporalTokenSupplier hanging = ns -> {
            release.await(10, TimeUnit.SECONDS);
            return Optional.empty();
        };
        RecordingService service = start(new ControlPlaneTemporalTokens(
                Optional.of(hanging), Clock.systemUTC(), Executors.newCachedThreadPool()));
        try {
            long startNanos = System.nanoTime();
            StatusRuntimeException thrown = (StatusRuntimeException) catchCall(() -> stubs.blockingStub()
                    .withDeadlineAfter(300, TimeUnit.MILLISECONDS)
                    .startWorkflowExecution(StartWorkflowExecutionRequest.newBuilder()
                            .setNamespace("ns-a")
                            .setWorkflowId("wf")
                            .build()));
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

            assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
            assertThat(elapsedMs).isLessThan(5000);
            assertThat(service.requestCountFor("StartWorkflowExecution")).isZero();
        } finally {
            release.countDown();
        }
    }

    @Test
    void interceptorIsInstalledOnlyWhenASourceIsPresent() {
        assertThat(TemporalConfig.stubsOptions("localhost:7233", noSource())
                        .build()
                        .getGrpcClientInterceptors())
                .isNullOrEmpty();
        assertThat(TemporalConfig.stubsOptions("localhost:7233", tokensFor(Map.of()))
                        .build()
                        .getGrpcClientInterceptors())
                .singleElement()
                .isInstanceOf(TemporalNamespaceCredentialInterceptor.class);
    }

    @Test
    void callsNamingTwoNamespaces_carryTwoDifferentTokens() throws Exception {
        RecordingService service = start(tokensFor(Map.of("ns-a", "tok-a", "ns-b", "tok-b")));

        stubs.blockingStub()
                .startWorkflowExecution(StartWorkflowExecutionRequest.newBuilder()
                        .setNamespace("ns-a")
                        .setWorkflowId("wf")
                        .build());
        stubs.blockingStub()
                .startWorkflowExecution(StartWorkflowExecutionRequest.newBuilder()
                        .setNamespace("ns-b")
                        .setWorkflowId("wf")
                        .build());

        assertThat(service.authsFor("StartWorkflowExecution")).containsExactly("Bearer tok-a", "Bearer tok-b");
    }

    @Test
    void getSystemInfo_carriesNoHeader() throws Exception {
        RecordingService service = start(tokensFor(Map.of("ns-a", "tok-a")));

        stubs.blockingStub().getSystemInfo(GetSystemInfoRequest.newBuilder().build());

        assertThat(service.authFor("GetSystemInfo")).isNull();
    }

    @Test
    void noSource_noHeaderOnAnyCall() throws Exception {
        ControlPlaneTemporalTokens tokens = noSource();
        assertThat(tokens.issuesTokens()).isFalse();
        RecordingService service = start(tokens);

        stubs.blockingStub()
                .startWorkflowExecution(StartWorkflowExecutionRequest.newBuilder()
                        .setNamespace("ns-a")
                        .setWorkflowId("wf")
                        .build());

        assertThat(service.authFor("StartWorkflowExecution")).isNull();
    }

    @Test
    void unavailableToken_failsUnauthenticated_recordsNoRequest_leaksNoToken() throws Exception {
        TemporalTokenSupplier failing = ns -> {
            throw new IOException("token source unreachable, definitely-not-a-token-value");
        };
        ControlPlaneTemporalTokens tokens = new ControlPlaneTemporalTokens(
                Optional.of(failing), Clock.systemUTC(), Executors.newCachedThreadPool());
        RecordingService service = start(tokens);

        StatusRuntimeException thrown = (StatusRuntimeException) catchCall(() -> stubs.blockingStub()
                .startWorkflowExecution(StartWorkflowExecutionRequest.newBuilder()
                        .setNamespace("ns-a")
                        .setWorkflowId("wf")
                        .build()));

        assertThat(thrown.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(thrown.getStatus().getDescription()).contains("ns-a");
        assertThat(thrown.getStatus().getDescription()).doesNotContain("definitely-not-a-token-value");
        assertThat(thrown.getMessage()).doesNotContain("definitely-not-a-token-value");
        assertThat(service.requestCountFor("StartWorkflowExecution")).isZero();
    }

    private static Throwable catchCall(Runnable call) {
        try {
            call.run();
            throw new AssertionError("expected the call to fail");
        } catch (Throwable t) {
            return t;
        }
    }

    /** Records each method's authorization header and request count; never records the request body. */
    private static final class RecordingService extends WorkflowServiceGrpc.WorkflowServiceImplBase {
        private final Map<String, List<String>> auths = new ConcurrentHashMap<>();
        private final Map<String, Integer> counts = new ConcurrentHashMap<>();

        private void record(String method) {
            String auth = AUTH_CONTEXT.get();
            auths.computeIfAbsent(method, k -> new ArrayList<>()).add(auth);
            counts.merge(method, 1, Integer::sum);
        }

        String authFor(String method) {
            List<String> list = auths.get(method);
            return list == null || list.isEmpty() ? null : list.get(list.size() - 1);
        }

        List<String> authsFor(String method) {
            return auths.getOrDefault(method, List.of());
        }

        int requestCountFor(String method) {
            return counts.getOrDefault(method, 0);
        }

        @Override
        public void startWorkflowExecution(
                StartWorkflowExecutionRequest request,
                StreamObserver<StartWorkflowExecutionResponse> responseObserver) {
            record("StartWorkflowExecution");
            responseObserver.onNext(StartWorkflowExecutionResponse.newBuilder().build());
            responseObserver.onCompleted();
        }

        @Override
        public void signalWorkflowExecution(
                SignalWorkflowExecutionRequest request,
                StreamObserver<SignalWorkflowExecutionResponse> responseObserver) {
            record("SignalWorkflowExecution");
            responseObserver.onNext(SignalWorkflowExecutionResponse.newBuilder().build());
            responseObserver.onCompleted();
        }

        @Override
        public void terminateWorkflowExecution(
                TerminateWorkflowExecutionRequest request,
                StreamObserver<TerminateWorkflowExecutionResponse> responseObserver) {
            record("TerminateWorkflowExecution");
            responseObserver.onNext(
                    TerminateWorkflowExecutionResponse.newBuilder().build());
            responseObserver.onCompleted();
        }

        @Override
        public void describeTaskQueue(
                DescribeTaskQueueRequest request, StreamObserver<DescribeTaskQueueResponse> responseObserver) {
            record("DescribeTaskQueue");
            responseObserver.onNext(DescribeTaskQueueResponse.newBuilder().build());
            responseObserver.onCompleted();
        }

        @Override
        public void getSystemInfo(
                GetSystemInfoRequest request, StreamObserver<GetSystemInfoResponse> responseObserver) {
            record("GetSystemInfo");
            responseObserver.onNext(GetSystemInfoResponse.newBuilder().build());
            responseObserver.onCompleted();
        }
    }
}
