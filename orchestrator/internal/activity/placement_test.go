package activity

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/apiclient"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/prompt"
)

func TestCheckNodePlacementReturnsDecision(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"allowed":false,"reason":"fleet offline"}`))
	}))
	defer srv.Close()

	client := apiclient.NewClient(srv.URL)
	activities := NewActivities(client, prompt.NewResolver(), testConfig(), nil)

	result, err := activities.CheckNodePlacement(context.Background(), CheckNodePlacementParams{
		RunID:           uuid.New(),
		NodeExecutionID: uuid.New(),
	})
	require.NoError(t, err)
	assert.False(t, result.Allowed)
	assert.Equal(t, "fleet offline", result.Reason)
}

// TestCheckNodePlacementFailsOpenIsNotTheActivitysJob asserts the activity returns an
// error rather than a permissive decision: the workflow's retry policy decides what a
// transport failure means, and an activity that invented "allowed" would hide an outage
// as a successful check.
func TestCheckNodePlacementFailsOpenIsNotTheActivitysJob(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	client := apiclient.NewClient(srv.URL)
	activities := NewActivities(client, prompt.NewResolver(), testConfig(), nil)

	result, err := activities.CheckNodePlacement(context.Background(), CheckNodePlacementParams{
		RunID:           uuid.New(),
		NodeExecutionID: uuid.New(),
	})
	require.Error(t, err)
	assert.False(t, result.Allowed)
}
