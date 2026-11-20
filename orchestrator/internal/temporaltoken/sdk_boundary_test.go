package temporaltoken

import (
	"context"
	"net"
	"sync"
	"testing"
	"time"

	"go.temporal.io/api/workflowservice/v1"
	"go.temporal.io/sdk/client"
	"google.golang.org/grpc"
	"google.golang.org/grpc/metadata"
)

// recordingServer is a minimal Temporal frontend double. It answers GetSystemInfo (called
// eagerly by client.Dial) and DescribeNamespace, and records each call's "authorization"
// metadata so a test can assert what the SDK actually puts on the wire.
type recordingServer struct {
	workflowservice.UnimplementedWorkflowServiceServer

	mu    sync.Mutex
	auths []string
}

func (s *recordingServer) GetSystemInfo(
	ctx context.Context, _ *workflowservice.GetSystemInfoRequest,
) (*workflowservice.GetSystemInfoResponse, error) {
	s.record(ctx)
	return &workflowservice.GetSystemInfoResponse{}, nil
}

func (s *recordingServer) DescribeNamespace(
	ctx context.Context, _ *workflowservice.DescribeNamespaceRequest,
) (*workflowservice.DescribeNamespaceResponse, error) {
	s.record(ctx)
	return &workflowservice.DescribeNamespaceResponse{}, nil
}

func (s *recordingServer) record(ctx context.Context) {
	md, _ := metadata.FromIncomingContext(ctx)
	auth := ""
	if vs := md.Get("authorization"); len(vs) > 0 {
		auth = vs[0]
	}
	s.mu.Lock()
	s.auths = append(s.auths, auth)
	s.mu.Unlock()
}

func (s *recordingServer) lastAuth() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.auths) == 0 {
		return ""
	}
	return s.auths[len(s.auths)-1]
}

func startRecordingServer(t *testing.T) (*recordingServer, string) {
	t.Helper()
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	srv := &recordingServer{}
	gs := grpc.NewServer()
	workflowservice.RegisterWorkflowServiceServer(gs, srv)
	go func() {
		_ = gs.Serve(lis)
	}()
	t.Cleanup(gs.Stop)
	return srv, lis.Addr().String()
}

func dial(t *testing.T, addr, namespace string, cred func(context.Context) (string, error)) client.Client {
	t.Helper()
	c, err := client.Dial(client.Options{
		HostPort:  addr,
		Namespace: namespace,
		ConnectionOptions: client.ConnectionOptions{
			TLSDisabled: true,
		},
		Credentials: client.NewAPIKeyDynamicCredentials(cred),
	})
	if err != nil {
		t.Fatalf("client.Dial: %v", err)
	}
	t.Cleanup(c.Close)
	return c
}

func TestSDKBoundary_PushedTokenGoesOutAsBearer(t *testing.T) {
	srv, addr := startRecordingServer(t)
	h := NewHolder(nil)
	h.Update("ns", Bearer(NewToken("secret-token-1", time.Now().Add(time.Hour))))

	c := dial(t, addr, "ns", h.Credential("ns"))
	// client.Dial already made a GetSystemInfo call; it must have carried the pushed token.
	if got := srv.lastAuth(); got != "Bearer secret-token-1" {
		t.Fatalf("authorization on dial = %q, want %q", got, "Bearer secret-token-1")
	}

	// A new push applies to the next call on the same client, without redialling.
	h.Update("ns", Bearer(NewToken("secret-token-2", time.Now().Add(time.Hour))))
	req := &workflowservice.DescribeNamespaceRequest{Namespace: "ns"}
	if _, err := c.WorkflowService().DescribeNamespace(context.Background(), req); err != nil {
		t.Fatalf("DescribeNamespace: %v", err)
	}
	if got := srv.lastAuth(); got != "Bearer secret-token-2" {
		t.Fatalf("authorization after repush = %q, want %q", got, "Bearer secret-token-2")
	}
}

func TestSDKBoundary_NoneSendsNoAuthorizationHeader(t *testing.T) {
	srv, addr := startRecordingServer(t)
	h := NewHolder(nil)
	h.Update("ns", None())

	dial(t, addr, "ns", h.Credential("ns"))
	if got := srv.lastAuth(); got != "" {
		t.Fatalf("authorization with None() = %q, want empty", got)
	}
}
