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

func TestControlPlaneSuppliesNoAgentCredentials(t *testing.T) {
	client := &fakeTokenClient{}

	_, ok, err := NewControlPlane(client).AgentCredentials(context.Background(), uuid.New())

	require.NoError(t, err)
	assert.False(t, ok, "prepare already returned the agent credentials")
	assert.Zero(t, client.calls)
}
