package credential

import (
	"context"
	"errors"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type fakeTokenClient struct {
	token  string
	err    error
	gotRun uuid.UUID
	calls  int
}

func (f *fakeTokenClient) GitHubToken(_ context.Context, runID uuid.UUID) (string, error) {
	f.calls++
	f.gotRun = runID
	return f.token, f.err
}

func TestControlPlaneGitHubTokenAsksTheClientPerRun(t *testing.T) {
	client := &fakeTokenClient{token: "ghs_fromapi"}
	runID := uuid.New()

	tok, err := NewControlPlane(client).GitHubToken(context.Background(), runID)

	require.NoError(t, err)
	assert.Equal(t, "ghs_fromapi", tok.Value)
	assert.Equal(t, runID, client.gotRun)
	assert.True(t, tok.ExpiresAt.IsZero(), "the API does not report an expiry, so the Worker must not cache")
}

func TestControlPlaneGitHubTokenPropagatesTheClientError(t *testing.T) {
	boom := errors.New("api error 503")

	_, err := NewControlPlane(&fakeTokenClient{err: boom}).GitHubToken(context.Background(), uuid.New())

	assert.ErrorIs(t, err, boom)
}

func TestControlPlaneRejectsABlankToken(t *testing.T) {
	_, err := NewControlPlane(&fakeTokenClient{token: ""}).GitHubToken(context.Background(), uuid.New())

	require.Error(t, err, "a blank token handed to a pod would surface as a confusing git failure")
}

func TestControlPlanePassesOnWhatPrepareAttached(t *testing.T) {
	client := &fakeTokenClient{}
	attached := Agent{
		ClaudeOAuthToken: "sk-ant-attached",
		Registry:         &Registry{Host: "registry.example.test", Username: "puller", Password: "secret"},
	}

	agent, err := NewControlPlane(client).AgentCredentials(context.Background(), uuid.New(), attached, true)

	require.NoError(t, err)
	assert.Equal(t, attached, agent)
	assert.Zero(t, client.calls, "agent credentials never need a separate API call: prepare already attached them")
}

func TestControlPlaneWithoutAnAttachedTokenFailsAClaudeNode(t *testing.T) {
	client := &fakeTokenClient{}
	runID := uuid.New()

	_, err := NewControlPlane(client).AgentCredentials(context.Background(), runID, Agent{}, true)

	require.Error(t, err)
	assert.True(t, errors.Is(err, ErrNoClaudeToken))
	assert.Contains(t, err.Error(), runID.String())
}

func TestControlPlaneScriptNodeNeedsNoToken(t *testing.T) {
	client := &fakeTokenClient{}

	agent, err := NewControlPlane(client).AgentCredentials(context.Background(), uuid.New(), Agent{}, false)

	require.NoError(t, err)
	assert.Empty(t, agent.ClaudeOAuthToken)
}

// panicTokenClient fails the test the instant any TokenClient method is called -- a control-plane
// Worker holds nothing, so its health check must never reach the API server at all.
type panicTokenClient struct{ t *testing.T }

func (p panicTokenClient) GitHubToken(context.Context, uuid.UUID) (string, error) {
	p.t.Fatal("control-plane CheckHealth must never call TokenClient")
	return "", nil
}

func TestControlPlaneCheckHealthReportsEveryKindNotHeldAndCallsNoClient(t *testing.T) {
	got := NewControlPlane(panicTokenClient{t: t}).CheckHealth(context.Background())

	assert.Len(t, got, 3)
	for _, r := range got {
		assert.Equal(t, StatusNotHeld, r.Status, "%s", r.Kind)
		assert.False(t, r.CheckedAt.IsZero())
	}
}
