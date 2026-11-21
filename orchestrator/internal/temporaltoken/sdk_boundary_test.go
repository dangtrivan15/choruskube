package temporaltoken

import (
	"context"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
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

	mu       sync.Mutex
	recorded []string
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
	s.recorded = append(s.recorded, auth)
	s.mu.Unlock()
}

// auths returns the authorization header of every call recorded so far, "" for a call that
// carried none.
func (s *recordingServer) auths() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.recorded...)
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
	require.NoError(t, err, "client.Dial")
	t.Cleanup(c.Close)
	return c
}

func describe(c client.Client) error {
	req := &workflowservice.DescribeNamespaceRequest{Namespace: "ns"}
	_, err := c.WorkflowService().DescribeNamespace(context.Background(), req)
	return err
}

func TestSDKBoundary_PushedTokenGoesOutAsBearer(t *testing.T) {
	srv, addr := startRecordingServer(t)
	h := NewHolder(nil)
	h.Update("ns", Bearer(NewToken("secret-token-1", time.Now().Add(time.Hour))))

	c := dial(t, addr, "ns", h.Credential("ns"))
	// client.Dial already made a GetSystemInfo call; it must have carried the pushed token.
	onDial := srv.auths()
	require.NotEmpty(t, onDial)
	for _, auth := range onDial {
		assert.Equal(t, "Bearer secret-token-1", auth)
	}

	// A new push applies to the next call on the same client, without redialling.
	h.Update("ns", Bearer(NewToken("secret-token-2", time.Now().Add(time.Hour))))
	require.NoError(t, describe(c))
	got := srv.auths()
	assert.Equal(t, "Bearer secret-token-2", got[len(got)-1])
}

func TestSDKBoundary_NoneSendsNoAuthorizationHeader(t *testing.T) {
	srv, addr := startRecordingServer(t)
	h := NewHolder(nil)
	h.Update("ns", None())

	c := dial(t, addr, "ns", h.Credential("ns"))
	require.NoError(t, describe(c))
	// The dial's GetSystemInfo and DescribeNamespace both reached the server, bare.
	got := srv.auths()
	require.GreaterOrEqual(t, len(got), 2)
	for _, auth := range got {
		assert.Empty(t, auth)
	}
}

func TestSDKBoundary_ExpiredTokenFailsTheCallInsteadOfSendingIt(t *testing.T) {
	srv, addr := startRecordingServer(t)
	clk := newClock(time.Now())
	h := NewHolder(clk.Now)
	h.Update("ns", Bearer(NewToken("secret-token-1", clk.Now().Add(time.Minute))))
	c := dial(t, addr, "ns", h.Credential("ns"))
	sent := srv.auths()

	clk.advance(2 * time.Minute)
	require.Error(t, describe(c), "a call on an expired token must fail")
	assert.Equal(t, sent, srv.auths(), "the call must not reach the server without a credential")
}
