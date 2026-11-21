package namespaces

import (
	"bytes"
	"context"
	"log"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.temporal.io/api/workflowservice/v1"
	"go.temporal.io/sdk/client"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/activity"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/apiclient"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/temporaltoken"
)

// fakeWorkflowService is a plaintext Temporal frontend double. It answers GetSystemInfo (called
// eagerly by client.Dial) and DescribeNamespace (called by worker.Start to load namespace
// capabilities) — refusing the latter with PermissionDenied for a configured namespace, the way a
// real frontend refuses a caller with no valid, or no sufficient, credential. Poll calls are left
// unimplemented: the SDK's background pollers retry them on their own and that retry never blocks
// Serve.
type fakeWorkflowService struct {
	workflowservice.UnimplementedWorkflowServiceServer

	mu              sync.Mutex
	refused         map[string]bool
	auths           map[string][]string
	describeNSCalls int
	systemInfoCalls int
}

func newFakeWorkflowService() *fakeWorkflowService {
	return &fakeWorkflowService{refused: map[string]bool{}, auths: map[string][]string{}}
}

func (s *fakeWorkflowService) setRefused(ns string, refused bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.refused[ns] = refused
}

func (s *fakeWorkflowService) recordAuth(method string, ctx context.Context) {
	md, _ := metadata.FromIncomingContext(ctx)
	auth := ""
	if vs := md.Get("authorization"); len(vs) > 0 {
		auth = vs[0]
	}
	s.mu.Lock()
	s.auths[method] = append(s.auths[method], auth)
	s.mu.Unlock()
}

func (s *fakeWorkflowService) authsFor(method string) []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.auths[method]...)
}

func (s *fakeWorkflowService) GetSystemInfo(
	ctx context.Context, _ *workflowservice.GetSystemInfoRequest,
) (*workflowservice.GetSystemInfoResponse, error) {
	s.recordAuth("GetSystemInfo", ctx)
	s.mu.Lock()
	s.systemInfoCalls++
	s.mu.Unlock()
	return &workflowservice.GetSystemInfoResponse{}, nil
}

func (s *fakeWorkflowService) DescribeNamespace(
	ctx context.Context, req *workflowservice.DescribeNamespaceRequest,
) (*workflowservice.DescribeNamespaceResponse, error) {
	s.recordAuth("DescribeNamespace", ctx)
	s.mu.Lock()
	s.describeNSCalls++
	refused := s.refused[req.GetNamespace()]
	s.mu.Unlock()
	if refused {
		return nil, status.Error(codes.PermissionDenied, "namespace refused")
	}
	return &workflowservice.DescribeNamespaceResponse{}, nil
}

func (s *fakeWorkflowService) systemInfoCallCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.systemInfoCalls
}

func startFakeWorkflowService(t *testing.T) (*fakeWorkflowService, string) {
	t.Helper()
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	srv := newFakeWorkflowService()
	gs := grpc.NewServer()
	workflowservice.RegisterWorkflowServiceServer(gs, srv)
	go func() {
		_ = gs.Serve(lis)
	}()
	t.Cleanup(gs.Stop)
	return srv, lis.Addr().String()
}

func testSupervisorAt(address string) *Supervisor {
	return NewSupervisor(address, activity.NewActivities(nil, nil, nil), temporaltoken.NewHolder(nil))
}

func captureSupervisorLog(t *testing.T, fn func()) string {
	t.Helper()
	var buf bytes.Buffer
	prevOut := log.Writer()
	prevFlags := log.Flags()
	log.SetOutput(&buf)
	log.SetFlags(0)
	defer func() {
		log.SetOutput(prevOut)
		log.SetFlags(prevFlags)
	}()
	fn()
	return buf.String()
}

func TestServeConfigured_NoTokenNeeded_ServesAtOnce(t *testing.T) {
	_, addr := startFakeWorkflowService(t)
	sup := testSupervisorAt(addr)
	t.Cleanup(sup.StopAll)

	err := sup.ServeConfigured("ns-configured")

	require.NoError(t, err)
	assert.Equal(t, 1, sup.Count())
}

func TestServeConfigured_UnreachableAddress_ReturnsErrUnreachable(t *testing.T) {
	sup := testSupervisorAt(unreachableAddress)

	err := sup.ServeConfigured("ns-configured")

	require.Error(t, err)
	assert.ErrorIs(t, err, ErrUnreachable)
	assert.Equal(t, 0, sup.Count())
}

// Mirrors Serve's dial: TLSDisabled explicitly, and a credential read fresh from the holder on
// every call rather than fixed at dial time.
func dialLikeServe(t *testing.T, addr, ns string, tokens *temporaltoken.Holder) client.Client {
	t.Helper()
	c, err := client.Dial(client.Options{
		HostPort:  addr,
		Namespace: ns,
		ConnectionOptions: client.ConnectionOptions{
			TLSDisabled: true,
		},
		Credentials: client.NewAPIKeyDynamicCredentials(tokens.Credential(ns)),
	})
	require.NoError(t, err)
	t.Cleanup(c.Close)
	return c
}

func describeNS(c client.Client, ns string) error {
	_, err := c.WorkflowService().DescribeNamespace(
		context.Background(), &workflowservice.DescribeNamespaceRequest{Namespace: ns})
	return err
}

// Exercises the exact dial Serve performs: every call on a namespace's connection carries its
// current token, and a token renewed later is used on the next call without a new connection
// (GetSystemInfo, which client.Dial calls once up front, is never called again).
func TestConnection_EachCallCarriesTheCurrentToken_RenewedWithoutRedialing(t *testing.T) {
	srv, addr := startFakeWorkflowService(t)
	tokens := temporaltoken.NewHolder(nil)
	tokens.Update("ns-a", temporaltoken.Bearer(temporaltoken.NewToken("tok-1", time.Now().Add(time.Hour))))

	c := dialLikeServe(t, addr, "ns-a", tokens)
	require.NoError(t, describeNS(c, "ns-a"))
	firstAuths := srv.authsFor("DescribeNamespace")
	require.NotEmpty(t, firstAuths)
	assert.Equal(t, "Bearer tok-1", firstAuths[len(firstAuths)-1])
	assert.Equal(t, 1, srv.systemInfoCallCount(), "client.Dial must have made exactly one GetSystemInfo call")

	tokens.Update("ns-a", temporaltoken.Bearer(temporaltoken.NewToken("tok-2", time.Now().Add(time.Hour))))
	require.NoError(t, describeNS(c, "ns-a"))
	secondAuths := srv.authsFor("DescribeNamespace")
	assert.Equal(t, "Bearer tok-2", secondAuths[len(secondAuths)-1])

	// No redial happened for the renewal: still exactly one GetSystemInfo call.
	assert.Equal(t, 1, srv.systemInfoCallCount())
}

// The fake frontend is plaintext-only: a client that insists on TLS cannot complete even the
// eager GetSystemInfo call client.Dial makes, which is what proves Serve's own TLSDisabled dial
// is the reason it succeeds against the same server.
func TestConnection_StaysPlaintext_ATLSAttemptFailsAgainstThisServer(t *testing.T) {
	_, addr := startFakeWorkflowService(t)
	tokens := temporaltoken.NewHolder(nil)

	_, err := client.Dial(client.Options{
		HostPort:    addr,
		Namespace:   "ns-a",
		Credentials: client.NewAPIKeyDynamicCredentials(tokens.Credential("ns-a")),
		// ConnectionOptions.TLSDisabled left false: TLS is attempted against a plaintext server.
	})
	require.Error(t, err, "a TLS client must not complete a handshake against a plaintext-only server")
}

func TestStartup_RefusalBeforeAnyRoster_DoesNotExit_ThenServedOnceTheFirstRosterCarriesTheToken(t *testing.T) {
	srv, addr := startFakeWorkflowService(t)
	srv.setRefused("ns-configured", true)
	sup := testSupervisorAt(addr)
	t.Cleanup(sup.StopAll)

	err := sup.ServeConfigured("ns-configured")
	require.NoError(t, err, "a refusal at startup, with no token yet, must not be treated as fatal")
	assert.Equal(t, 0, sup.Count())

	// The first roster, fetched at startup, carries the namespace's token.
	srv.setRefused("ns-configured", false)
	future := time.Now().Add(time.Hour)
	roster := &fixedRoster{
		namespaces: []string{"ns-configured"},
		credentials: map[string]apiclient.NamespaceCredential{
			"ns-configured": {State: "bearer", Token: "tok-a", ExpiresAt: future},
		},
	}

	require.NoError(t, refreshOnce(context.Background(), sup, roster, "ns-configured"))
	assert.Equal(t, 1, sup.Count(), "the namespace must now be served once its token has arrived")
}

func TestRefreshOnce_RefusalWhileHoldingTheToken_IsFatal(t *testing.T) {
	srv, addr := startFakeWorkflowService(t)
	srv.setRefused("ns-a", true)
	sup := testSupervisorAt(addr)
	t.Cleanup(sup.StopAll)

	future := time.Now().Add(time.Hour)
	roster := &fixedRoster{
		namespaces: []string{"ns-a"},
		credentials: map[string]apiclient.NamespaceCredential{
			"ns-a": {State: "bearer", Token: "tok-a", ExpiresAt: future},
		},
	}

	err := refreshOnce(context.Background(), sup, roster, "ns-a")

	require.Error(t, err)
	assert.ErrorIs(t, err, ErrRefused)
}

func TestRefreshOnce_RefusalWhileRosterMarksTheNamespaceNone_IsFatal(t *testing.T) {
	srv, addr := startFakeWorkflowService(t)
	srv.setRefused("ns-a", true)
	sup := testSupervisorAt(addr)
	t.Cleanup(sup.StopAll)

	roster := &fixedRoster{
		namespaces:  []string{"ns-a"},
		credentials: map[string]apiclient.NamespaceCredential{"ns-a": {State: "none"}},
	}

	err := refreshOnce(context.Background(), sup, roster, "ns-a")

	require.Error(t, err)
	assert.ErrorIs(t, err, ErrRefused)
}

// A refusal on a namespace that is neither held nor marked none — the token source simply hasn't
// caught up yet — must not be fatal: it is retried, same as an unreachable Temporal.
func TestRefreshOnce_RefusalWhileUnavailable_IsNotFatal(t *testing.T) {
	srv, addr := startFakeWorkflowService(t)
	srv.setRefused("ns-a", true)
	sup := testSupervisorAt(addr)
	t.Cleanup(sup.StopAll)

	roster := &fixedRoster{
		namespaces:  []string{"ns-a"},
		credentials: map[string]apiclient.NamespaceCredential{"ns-a": {State: "unavailable"}},
	}

	err := refreshOnce(context.Background(), sup, roster, "ns-a")

	require.NoError(t, err)
	assert.Equal(t, 0, sup.Count())
}

func TestServe_LogsNeverContainAToken(t *testing.T) {
	_, addr := startFakeWorkflowService(t)
	sup := testSupervisorAt(addr)
	t.Cleanup(sup.StopAll)
	sup.tokens.Update("ns-a", temporaltoken.Bearer(
		temporaltoken.NewToken("super-secret-token-xyz", time.Now().Add(time.Hour))))

	out := captureSupervisorLog(t, func() {
		require.NoError(t, sup.Serve("ns-a"))
	})

	assert.NotContains(t, out, "super-secret-token-xyz")
}
