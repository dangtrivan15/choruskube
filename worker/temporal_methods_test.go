package worker

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"net"
	"os"
	"reflect"
	"regexp"
	"strings"
	"sync"
	"testing"
	"time"

	commonpb "go.temporal.io/api/common/v1"
	namespacepb "go.temporal.io/api/namespace/v1"
	"go.temporal.io/api/workflowservice/v1"
	sdkactivity "go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/client"
	"go.temporal.io/sdk/temporal"
	tworker "go.temporal.io/sdk/worker"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	healthpb "google.golang.org/grpc/health/grpc_health_v1"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/types/known/durationpb"
	"google.golang.org/protobuf/types/known/timestamppb"
)

// This file pins the set of Temporal gRPC methods the Worker calls against the table in
// worker/README.md, and that every call carries the Worker's bearer -- the choices recorded in
// docs/decisions/2026-11-14---02-worker-temporal-methods-pinned-to-the-readme.md.

// recordedCall is one gRPC call the fake received, on any service, with the metadata it carried.
type recordedCall struct {
	method   string
	metadata metadata.MD
}

// methodRecorder records every call a *grpc.Server receives through its unary and stream
// interceptors, including calls to a service the fake never registered, and lets a test wait
// for a method to have been recorded a number of times.
type methodRecorder struct {
	mu     sync.Mutex
	calls  []recordedCall
	notify chan struct{}
}

func newMethodRecorder() *methodRecorder {
	return &methodRecorder{notify: make(chan struct{}, 1)}
}

func (r *methodRecorder) record(ctx context.Context, method string) {
	md, _ := metadata.FromIncomingContext(ctx)
	r.mu.Lock()
	r.calls = append(r.calls, recordedCall{method: method, metadata: md.Copy()})
	r.mu.Unlock()
	select {
	case r.notify <- struct{}{}:
	default:
	}
}

func (r *methodRecorder) snapshot() []recordedCall {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]recordedCall, len(r.calls))
	copy(out, r.calls)
	return out
}

func (r *methodRecorder) countOf(method string) int {
	n := 0
	for _, c := range r.snapshot() {
		if c.method == method {
			n++
		}
	}
	return n
}

// waitForCount blocks until method has been recorded at least n times, or deadline passes. On
// timeout it marks t failed, by name, but still returns so the caller's remaining assertions run
// -- a renamed call is reported by name rather than as a bare timeout.
func (r *methodRecorder) waitForCount(t *testing.T, method string, n int, deadline time.Time) {
	for {
		if r.countOf(method) >= n {
			return
		}
		remaining := time.Until(deadline)
		if remaining <= 0 {
			t.Errorf("timed out waiting for %dx %s, recorded %d", n, method, r.countOf(method))
			return
		}
		select {
		case <-r.notify:
		case <-time.After(remaining):
		}
	}
}

func (r *methodRecorder) unaryInterceptor(ctx context.Context, req interface{}, info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (interface{}, error) {
	r.record(ctx, info.FullMethod)
	return handler(ctx, req)
}

func (r *methodRecorder) streamInterceptor(srv interface{}, ss grpc.ServerStream, info *grpc.StreamServerInfo, handler grpc.StreamHandler) error {
	r.record(ss.Context(), info.FullMethod)
	return handler(srv, ss)
}

// unknownServiceHandler answers a call to any service the fake did not register with the same
// UNIMPLEMENTED a proxy's method allowlist gives a call outside it, so a new call cannot hide
// behind the refusal. gRPC only runs an unregistered service's call through the stream
// interceptor, which is why the fake needs one at all.
func unknownServiceHandler(srv interface{}, stream grpc.ServerStream) error {
	method, _ := grpc.MethodFromServerStream(stream)
	return status.Errorf(codes.Unimplemented, "method %s not implemented by fake", method)
}

// temporalFake answers just enough WorkflowService calls for the SDK to run every outcome a
// Worker drives: dial, start, poll, synchronous activity results, by-id completion and
// heartbeat, and stop. Every method it does not override returns UNIMPLEMENTED by embedding
// UnimplementedWorkflowServiceServer, the same answer the fake gives a call to another service.
type temporalFake struct {
	workflowservice.UnimplementedWorkflowServiceServer

	namespace string

	mu          sync.Mutex
	queue       []*workflowservice.PollActivityTaskQueueResponse
	cancelToken []byte
}

// enqueue adds one activity task the next PollActivityTaskQueue call hands out. taskToken must be
// unique per task; the fake matches heartbeats and responses to a scenario by it.
func (f *temporalFake) enqueue(activityType, taskToken string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.queue = append(f.queue, &workflowservice.PollActivityTaskQueueResponse{
		TaskToken:              []byte(taskToken),
		WorkflowNamespace:      f.namespace,
		ActivityType:           &commonpb.ActivityType{Name: activityType},
		ActivityId:             taskToken,
		ScheduledTime:          timestamppb.Now(),
		StartedTime:            timestamppb.Now(),
		ScheduleToCloseTimeout: durationpb.New(30 * time.Second),
		StartToCloseTimeout:    durationpb.New(30 * time.Second),
		HeartbeatTimeout:       durationpb.New(30 * time.Second),
		Attempt:                1,
	})
}

// requestCancelOn marks taskToken as the one whose heartbeat answers CancelRequested=true.
func (f *temporalFake) requestCancelOn(taskToken string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.cancelToken = []byte(taskToken)
}

func (f *temporalFake) GetSystemInfo(context.Context, *workflowservice.GetSystemInfoRequest) (*workflowservice.GetSystemInfoResponse, error) {
	return &workflowservice.GetSystemInfoResponse{}, nil
}

func (f *temporalFake) DescribeNamespace(context.Context, *workflowservice.DescribeNamespaceRequest) (*workflowservice.DescribeNamespaceResponse, error) {
	return &workflowservice.DescribeNamespaceResponse{
		NamespaceInfo: &namespacepb.NamespaceInfo{
			Name:         f.namespace,
			Capabilities: &namespacepb.NamespaceInfo_Capabilities{WorkerHeartbeats: true},
		},
	}, nil
}

// PollActivityTaskQueue hands out one queued task per call until the queue is empty, then
// answers empty (no task) after a short delay -- a long poll's ordinary "nothing yet" -- and
// returns early when ctx ends, which is what lets w.Stop() return promptly.
func (f *temporalFake) PollActivityTaskQueue(ctx context.Context, _ *workflowservice.PollActivityTaskQueueRequest) (*workflowservice.PollActivityTaskQueueResponse, error) {
	f.mu.Lock()
	if len(f.queue) > 0 {
		task := f.queue[0]
		f.queue = f.queue[1:]
		f.mu.Unlock()
		return task, nil
	}
	f.mu.Unlock()

	select {
	case <-ctx.Done():
	case <-time.After(50 * time.Millisecond):
	}
	return &workflowservice.PollActivityTaskQueueResponse{}, nil
}

func (f *temporalFake) RecordActivityTaskHeartbeat(_ context.Context, req *workflowservice.RecordActivityTaskHeartbeatRequest) (*workflowservice.RecordActivityTaskHeartbeatResponse, error) {
	f.mu.Lock()
	cancel := f.cancelToken != nil && bytes.Equal(req.GetTaskToken(), f.cancelToken)
	f.mu.Unlock()
	return &workflowservice.RecordActivityTaskHeartbeatResponse{CancelRequested: cancel}, nil
}

func (f *temporalFake) RecordActivityTaskHeartbeatById(context.Context, *workflowservice.RecordActivityTaskHeartbeatByIdRequest) (*workflowservice.RecordActivityTaskHeartbeatByIdResponse, error) {
	return &workflowservice.RecordActivityTaskHeartbeatByIdResponse{}, nil
}

func (f *temporalFake) RespondActivityTaskCompleted(context.Context, *workflowservice.RespondActivityTaskCompletedRequest) (*workflowservice.RespondActivityTaskCompletedResponse, error) {
	return &workflowservice.RespondActivityTaskCompletedResponse{}, nil
}

func (f *temporalFake) RespondActivityTaskFailed(context.Context, *workflowservice.RespondActivityTaskFailedRequest) (*workflowservice.RespondActivityTaskFailedResponse, error) {
	return &workflowservice.RespondActivityTaskFailedResponse{}, nil
}

func (f *temporalFake) RespondActivityTaskCanceled(context.Context, *workflowservice.RespondActivityTaskCanceledRequest) (*workflowservice.RespondActivityTaskCanceledResponse, error) {
	return &workflowservice.RespondActivityTaskCanceledResponse{}, nil
}

func (f *temporalFake) RespondActivityTaskCompletedById(context.Context, *workflowservice.RespondActivityTaskCompletedByIdRequest) (*workflowservice.RespondActivityTaskCompletedByIdResponse, error) {
	return &workflowservice.RespondActivityTaskCompletedByIdResponse{}, nil
}

func (f *temporalFake) RespondActivityTaskFailedById(context.Context, *workflowservice.RespondActivityTaskFailedByIdRequest) (*workflowservice.RespondActivityTaskFailedByIdResponse, error) {
	return &workflowservice.RespondActivityTaskFailedByIdResponse{}, nil
}

func (f *temporalFake) RespondActivityTaskCanceledById(context.Context, *workflowservice.RespondActivityTaskCanceledByIdRequest) (*workflowservice.RespondActivityTaskCanceledByIdResponse, error) {
	return &workflowservice.RespondActivityTaskCanceledByIdResponse{}, nil
}

func (f *temporalFake) ShutdownWorker(context.Context, *workflowservice.ShutdownWorkerRequest) (*workflowservice.ShutdownWorkerResponse, error) {
	return &workflowservice.ShutdownWorkerResponse{}, nil
}

func (f *temporalFake) RecordWorkerHeartbeat(context.Context, *workflowservice.RecordWorkerHeartbeatRequest) (*workflowservice.RecordWorkerHeartbeatResponse, error) {
	return &workflowservice.RecordWorkerHeartbeatResponse{}, nil
}

var _ workflowservice.WorkflowServiceServer = (*temporalFake)(nil)

// newFakeServer starts a recording gRPC server on 127.0.0.1:0 and returns its address alongside
// the fake and the recorder driving it. The caller must Stop() the returned *grpc.Server.
func newFakeServer(t *testing.T, fake *temporalFake, rec *methodRecorder) (addr string, srv *grpc.Server) {
	t.Helper()
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	srv = grpc.NewServer(
		grpc.UnaryInterceptor(rec.unaryInterceptor),
		grpc.StreamInterceptor(rec.streamInterceptor),
		grpc.UnknownServiceHandler(unknownServiceHandler),
	)
	workflowservice.RegisterWorkflowServiceServer(srv, fake)
	go func() { _ = srv.Serve(lis) }()
	t.Cleanup(srv.Stop)
	return lis.Addr().String(), srv
}

// Probe activities. Each drives exactly one outcome the completer or the activity worker must
// turn into a WorkflowService call: a successful completion after a heartbeat, a failure with no
// heartbeat, and a heartbeat whose reply requests cancellation.

const (
	probeHeartbeatSucceedActivity = "probeHeartbeatSucceed"
	probeFailActivity             = "probeFail"
	probeHeartbeatCancelActivity  = "probeHeartbeatCancel"
)

func probeHeartbeatSucceed(ctx context.Context) (string, error) {
	sdkactivity.RecordHeartbeat(ctx, "progress")
	return "ok", nil
}

func probeFail(context.Context) (string, error) {
	return "", errors.New("probe activity failed on purpose")
}

func probeHeartbeatCancel(ctx context.Context) (string, error) {
	sdkactivity.RecordHeartbeat(ctx, "progress")
	<-ctx.Done()
	return "", ctx.Err()
}

func registerProbeActivities(w tworker.Worker) {
	w.RegisterActivityWithOptions(probeHeartbeatSucceed, sdkactivity.RegisterOptions{Name: probeHeartbeatSucceedActivity})
	w.RegisterActivityWithOptions(probeFail, sdkactivity.RegisterOptions{Name: probeFailActivity})
	w.RegisterActivityWithOptions(probeHeartbeatCancel, sdkactivity.RegisterOptions{Name: probeHeartbeatCancelActivity})
}

// countingCompletions wraps a temporalCompletions and counts each call by method name, so the
// test can prove by reflection that every method on the interface was actually exercised rather
// than merely declared.
type countingCompletions struct {
	temporalCompletions
	mu     sync.Mutex
	counts map[string]int
}

func newCountingCompletions(delegate temporalCompletions) *countingCompletions {
	return &countingCompletions{temporalCompletions: delegate, counts: map[string]int{}}
}

func (c *countingCompletions) count(name string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.counts[name]++
}

func (c *countingCompletions) countOf(name string) int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.counts[name]
}

func (c *countingCompletions) CompleteActivityByID(ctx context.Context, namespace, workflowID, runID, activityID string, result interface{}, err error) error {
	c.count("CompleteActivityByID")
	return c.temporalCompletions.CompleteActivityByID(ctx, namespace, workflowID, runID, activityID, result, err)
}

func (c *countingCompletions) RecordActivityHeartbeatByID(ctx context.Context, namespace, workflowID, runID, activityID string, details ...interface{}) error {
	c.count("RecordActivityHeartbeatByID")
	return c.temporalCompletions.RecordActivityHeartbeatByID(ctx, namespace, workflowID, runID, activityID, details...)
}

var _ temporalCompletions = (*countingCompletions)(nil)

// sectionHeadingRE finds the proxy section's heading; nextHeadingRE finds where it ends.
var (
	sectionHeadingRE = regexp.MustCompile(`(?m)^## Reaching Temporal through a proxy\s*$`)
	nextHeadingRE    = regexp.MustCompile(`(?m)^## `)
	codeSpanRE       = regexp.MustCompile("`([^`]+)`")
	separatorCellRE  = regexp.MustCompile(`^:?-+:?$`)
)

// readWorkerMethodTable parses the "Reaching Temporal through a proxy" section's table and
// returns every method name its first column spells in a code span. It is the only reader of
// that table's content, so a reformatted table fails here as a format error rather than as a
// changed method set.
func readWorkerMethodTable(readme []byte) ([]string, error) {
	loc := sectionHeadingRE.FindIndex(readme)
	if loc == nil {
		return nil, errors.New(`no "## Reaching Temporal through a proxy" section`)
	}
	rest := readme[loc[1]:]
	if end := nextHeadingRE.FindIndex(rest); end != nil {
		rest = rest[:end[0]]
	}

	var names []string
	sawRow := false
	for _, line := range strings.Split(string(rest), "\n") {
		line = strings.TrimSpace(line)
		if !strings.HasPrefix(line, "|") {
			continue
		}
		cells := strings.Split(strings.Trim(line, "|"), "|")
		first := strings.TrimSpace(cells[0])
		if first == "Method" || separatorCellRE.MatchString(first) {
			continue
		}
		sawRow = true
		spans := codeSpanRE.FindAllStringSubmatch(first, -1)
		if len(spans) == 0 {
			return nil, fmt.Errorf("method cell %q has no code span", first)
		}
		for _, m := range spans {
			names = append(names, m[1])
		}
	}
	if !sawRow {
		return nil, errors.New("no method table under the proxy section")
	}
	return names, nil
}

const workflowServicePrefix = "/temporal.api.workflowservice.v1.WorkflowService/"

// TestWorkerTemporalMethods drives every Temporal surface the Worker uses -- dial, start, poll,
// the three synchronous activity outcomes, the three by-id completions, a by-id heartbeat, one
// Worker heartbeat, and stop -- against a recording fake, then compares what it recorded with
// worker/README.md's table. A renamed, added or dropped gRPC call changes that comparison; so
// does an SDK call the completer's narrowed interface does not also grow to cover.
func TestWorkerTemporalMethods(t *testing.T) {
	rec := newMethodRecorder()
	fake := &temporalFake{namespace: "probe-ns"}
	addr, _ := newFakeServer(t, fake, rec)

	fake.enqueue(probeHeartbeatSucceedActivity, "task-succeed")
	fake.enqueue(probeFailActivity, "task-fail")
	fake.enqueue(probeHeartbeatCancelActivity, "task-cancel")
	fake.requestCancelOn("task-cancel")

	f := Fleet{Namespace: fake.namespace, TaskQueue: "probe-q", Token: "probe-token"}
	cfg := Config{TemporalAddress: addr, TemporalTLSDisabled: true}
	opts := clientOptions(f, cfg, newTokenCache([]Fleet{f}), fleetKey(f))
	// Shortened so the first Worker heartbeat -- otherwise every 60s -- fires inside this test.
	opts.WorkerHeartbeatInterval = 1 * time.Second

	c, err := client.Dial(opts)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}

	w := tworker.New(c, f.TaskQueue, fleetWorkerOptions())
	registerProbeActivities(w)
	if err := w.Start(); err != nil {
		t.Fatalf("start: %v", err)
	}

	deadline := time.Now().Add(10 * time.Second)

	// Synchronous scenarios: wait for each task token's own respond call.
	rec.waitForCount(t, workflowServicePrefix+"RespondActivityTaskCompleted", 1, deadline)
	rec.waitForCount(t, workflowServicePrefix+"RespondActivityTaskFailed", 1, deadline)
	rec.waitForCount(t, workflowServicePrefix+"RespondActivityTaskCanceled", 1, deadline)

	// By-id scenarios, through the same counting wrapper the completer uses.
	completions := newCountingCompletions(c)
	byIDResult := completions.CompleteActivityByID(context.Background(), fake.namespace, "wf-result", "", "act-result", "by-id ok", nil)
	if byIDResult != nil {
		t.Errorf("CompleteActivityByID (result) = %v, want nil", byIDResult)
	}
	byIDFailed := completions.CompleteActivityByID(context.Background(), fake.namespace, "wf-error", "", "act-error", nil, errors.New("by-id error"))
	if byIDFailed != nil {
		t.Errorf("CompleteActivityByID (error) = %v, want nil", byIDFailed)
	}
	byIDCanceled := completions.CompleteActivityByID(context.Background(), fake.namespace, "wf-canceled", "", "act-canceled", nil, temporal.NewCanceledError())
	if byIDCanceled != nil {
		t.Errorf("CompleteActivityByID (canceled) = %v, want nil", byIDCanceled)
	}
	if err := completions.RecordActivityHeartbeatByID(context.Background(), fake.namespace, "wf-heartbeat", "", "act-heartbeat"); err != nil {
		t.Errorf("RecordActivityHeartbeatByID = %v, want nil", err)
	}

	// One Worker heartbeat, then stop in the order serve's stop function uses.
	rec.waitForCount(t, workflowServicePrefix+"RecordWorkerHeartbeat", 1, deadline)

	w.Stop()
	c.Close()

	// Set comparison: the recorded WorkflowService methods against the README's table.
	readme, err := os.ReadFile("README.md")
	if err != nil {
		t.Fatalf("read README.md: %v", err)
	}
	tableNames, err := readWorkerMethodTable(readme)
	if err != nil {
		t.Fatalf("parse README.md's proxy section: %v", err)
	}
	want := map[string]bool{}
	for _, name := range tableNames {
		want[workflowServicePrefix+name] = true
	}

	got := map[string]bool{}
	var missingBearer []string
	for _, call := range rec.snapshot() {
		if !strings.HasPrefix(call.method, workflowServicePrefix) {
			continue
		}
		got[call.method] = true
		if !hasBearer(call.metadata) {
			missingBearer = append(missingBearer, call.method)
		}
	}

	for method := range want {
		if !got[method] {
			t.Errorf("table lists %s but the Worker never called it", method)
		}
	}
	for method := range got {
		if !want[method] {
			t.Errorf("Worker called %s but the README table does not list it", method)
		}
	}
	if len(missingBearer) > 0 {
		t.Errorf("calls with no %q bearer metadata: %v", "Bearer probe-token", missingBearer)
	}

	// Completion interface: every temporalCompletions method must have been called through
	// countingCompletions, or a widened interface could pass unnoticed.
	completionsType := reflect.TypeOf((*temporalCompletions)(nil)).Elem()
	for i := 0; i < completionsType.NumMethod(); i++ {
		name := completionsType.Method(i).Name
		if completions.countOf(name) == 0 {
			t.Errorf("temporalCompletions method %s was never called through countingCompletions", name)
		}
	}
}

// hasBearer reports whether md carries exactly "Bearer <token>" on "authorization", for any
// non-empty token -- the credential a proxy's JWT check depends on being present.
func hasBearer(md metadata.MD) bool {
	for _, v := range md.Get("authorization") {
		if strings.HasPrefix(v, "Bearer ") && len(v) > len("Bearer ") {
			return true
		}
	}
	return false
}

// TestTemporalMethodFakeRecordsUnservedCalls proves the fake sees a call it refuses, on its own
// registered service and on another, rather than gRPC refusing it before any interceptor runs --
// the mechanism the method-pin test above relies on to make an out-of-set call visible at all.
func TestTemporalMethodFakeRecordsUnservedCalls(t *testing.T) {
	rec := newMethodRecorder()
	fake := &temporalFake{namespace: "probe-ns"}
	addr, _ := newFakeServer(t, fake, rec)

	conn, err := grpc.NewClient(addr, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		t.Fatalf("dial fake: %v", err)
	}
	defer conn.Close()

	ctx := metadata.AppendToOutgoingContext(context.Background(), "authorization", "Bearer probe-token")

	_, err = workflowservice.NewWorkflowServiceClient(conn).ListNamespaces(ctx, &workflowservice.ListNamespacesRequest{})
	if status.Code(err) != codes.Unimplemented {
		t.Errorf("ListNamespaces = %v, want an UNIMPLEMENTED status", err)
	}

	_, err = healthpb.NewHealthClient(conn).Check(ctx, &healthpb.HealthCheckRequest{Service: "temporal.api.workflowservice.v1.WorkflowService"})
	if status.Code(err) != codes.Unimplemented {
		t.Errorf("health Check = %v, want an UNIMPLEMENTED status", err)
	}

	deadline := time.Now().Add(5 * time.Second)
	rec.waitForCount(t, workflowServicePrefix+"ListNamespaces", 1, deadline)
	rec.waitForCount(t, "/grpc.health.v1.Health/Check", 1, deadline)

	for _, method := range []string{workflowServicePrefix + "ListNamespaces", "/grpc.health.v1.Health/Check"} {
		found := false
		for _, call := range rec.snapshot() {
			if call.method != method {
				continue
			}
			found = true
			if !hasBearer(call.metadata) {
				t.Errorf("%s was recorded with no authorization metadata", method)
			}
		}
		if !found {
			t.Errorf("%s was never recorded", method)
		}
	}
}

// TestReadWorkerMethodTable exercises the parser's format errors directly, on fixtures, so the
// main test's failures are a changed method set, never a parser bug in disguise.
func TestReadWorkerMethodTable(t *testing.T) {
	t.Run("missing section", func(t *testing.T) {
		if _, err := readWorkerMethodTable([]byte("# Worker\n\nno proxy section here.\n")); err == nil {
			t.Fatal("want an error for a README with no proxy section")
		}
	})

	t.Run("method cell with no code span", func(t *testing.T) {
		md := "## Reaching Temporal through a proxy\n\n" +
			"| Method | When the Worker calls it | If refused |\n" +
			"|---|---|---|\n" +
			"| GetSystemInfo | when it dials | tolerated |\n"
		if _, err := readWorkerMethodTable([]byte(md)); err == nil {
			t.Fatal("want an error for a method cell with no code span")
		}
	})

	t.Run("cell with three code spans yields three names", func(t *testing.T) {
		md := "## Reaching Temporal through a proxy\n\n" +
			"| Method | When the Worker calls it | If refused |\n" +
			"|---|---|---|\n" +
			"| `RespondActivityTaskCompleted`, `RespondActivityTaskFailed`, `RespondActivityTaskCanceled` | results | retried |\n"
		names, err := readWorkerMethodTable([]byte(md))
		if err != nil {
			t.Fatalf("readWorkerMethodTable: %v", err)
		}
		want := []string{"RespondActivityTaskCompleted", "RespondActivityTaskFailed", "RespondActivityTaskCanceled"}
		if !reflect.DeepEqual(names, want) {
			t.Fatalf("names = %v, want %v", names, want)
		}
	})
}
