package activity

import (
	"context"
	"strings"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// fakeObjectStore is a test double for the object storage ObjectStore
type fakeObjectStore struct {
	objects map[string][]byte
}

func newFakeObjectStore() *fakeObjectStore {
	return &fakeObjectStore{objects: make(map[string][]byte)}
}

func (f *fakeObjectStore) PutObject(_ context.Context, key string, data []byte) error {
	f.objects[key] = data
	return nil
}

func (f *fakeObjectStore) GetObject(_ context.Context, key string) ([]byte, error) {
	return f.objects[key], nil
}

func TestBuildRunLogEntry_CompletedWithRouting(t *testing.T) {
	entry := buildRunLogEntry(AppendRunLogParams{
		NodeLabel: "test",
		Status:    "completed",
		Iteration: 1,
		Result:    "failed",
		RoutedTo:  "implement",
	})
	assert.Contains(t, entry, "## test (completed → implement, iteration 1)")
	assert.Contains(t, entry, "**Result:** failed")
	assert.NotContains(t, entry, "**Error:**")
	assert.NotContains(t, entry, "**Artifacts:**")
}

func TestBuildRunLogEntry_CompletedTerminal(t *testing.T) {
	entry := buildRunLogEntry(AppendRunLogParams{
		NodeLabel: "final",
		Status:    "completed",
		Iteration: 2,
		Result:    "done",
	})
	assert.Contains(t, entry, "## final (completed, iteration 2)")
	assert.Contains(t, entry, "**Result:** done")
}

func TestBuildRunLogEntry_FailedWithError(t *testing.T) {
	entry := buildRunLogEntry(AppendRunLogParams{
		NodeLabel:    "test",
		Status:       "failed",
		Iteration:    1,
		Result:       "",
		ErrorMessage: "Gradle timeout: 10000ms",
	})
	assert.Contains(t, entry, "## test (failed, iteration 1)")
	assert.Contains(t, entry, "**Error:** Gradle timeout: 10000ms")
}

func TestBuildRunLogEntry_WithArtifacts(t *testing.T) {
	entry := buildRunLogEntry(AppendRunLogParams{
		NodeLabel:    "implement",
		Status:       "completed",
		Iteration:    1,
		Result:       "completed",
		ArtifactRefs: `{"output":"runs/abc/def/out/"}`,
		RoutedTo:     "test",
	})
	assert.Contains(t, entry, `**Artifacts:** {"output":"runs/abc/def/out/"}`)
}

func TestBuildRunLogEntry_EmptyArtifactsOmitted(t *testing.T) {
	entry := buildRunLogEntry(AppendRunLogParams{
		NodeLabel:    "test",
		Status:       "completed",
		Iteration:    1,
		Result:       "passed",
		ArtifactRefs: "{}",
	})
	assert.NotContains(t, entry, "**Artifacts:**")
}

func TestBuildRunLogEntry_HumanGate(t *testing.T) {
	entry := buildRunLogEntry(AppendRunLogParams{
		NodeLabel: "human_review",
		Status:    "completed",
		Iteration: 1,
		Result:    "approved",
		RoutedTo:  "implement",
	})
	assert.Contains(t, entry, "## human_review (completed → implement, iteration 1)")
	assert.Contains(t, entry, "**Result:** approved")
}

func TestInitRunLog(t *testing.T) {
	store := newFakeObjectStore()
	acts := &Activities{objectStoreClient: store}
	runID := uuid.New()

	err := acts.InitRunLog(withWorkflowRunID(t, runID), InitRunLogParams{RunID: runID})
	require.NoError(t, err)

	key := "runs/" + runID.String() + "/run_log.md"
	data := store.objects[key]
	assert.True(t, strings.HasPrefix(string(data), "# Run Log — "+runID.String()))
}

func TestInitRunLog_WithOrgSlug(t *testing.T) {
	store := newFakeObjectStore()
	acts := &Activities{objectStoreClient: store}
	runID := uuid.New()

	err := acts.InitRunLog(withWorkflowRunID(t, runID), InitRunLogParams{RunID: runID, OrgSlug: "acme"})
	require.NoError(t, err)

	key := "acme/runs/" + runID.String() + "/run_log.md"
	data := store.objects[key]
	assert.True(t, strings.HasPrefix(string(data), "# Run Log — "+runID.String()))
}

func TestInitRunLog_EmptyOrgSlug(t *testing.T) {
	store := newFakeObjectStore()
	acts := &Activities{objectStoreClient: store}
	runID := uuid.New()

	err := acts.InitRunLog(withWorkflowRunID(t, runID), InitRunLogParams{RunID: runID, OrgSlug: ""})
	require.NoError(t, err)

	key := "runs/" + runID.String() + "/run_log.md"
	data := store.objects[key]
	assert.True(t, strings.HasPrefix(string(data), "# Run Log — "+runID.String()))
}

func TestAppendRunLog_FirstEntry(t *testing.T) {
	store := newFakeObjectStore()
	acts := &Activities{objectStoreClient: store}
	runID := uuid.New()

	acts.InitRunLog(withWorkflowRunID(t, runID), InitRunLogParams{RunID: runID})

	err := acts.AppendRunLog(withWorkflowRunID(t, runID), AppendRunLogParams{
		RunID:     runID,
		NodeLabel: "feature_request",
		Status:    "completed",
		Iteration: 1,
		Result:    "approved",
		RoutedTo:  "ai_draft_spec",
	})
	require.NoError(t, err)

	key := "runs/" + runID.String() + "/run_log.md"
	content := string(store.objects[key])
	assert.Contains(t, content, "# Run Log")
	assert.Contains(t, content, "## feature_request (completed → ai_draft_spec, iteration 1)")
}

func TestAppendRunLog_WithOrgSlug(t *testing.T) {
	store := newFakeObjectStore()
	acts := &Activities{objectStoreClient: store}
	runID := uuid.New()

	acts.InitRunLog(withWorkflowRunID(t, runID), InitRunLogParams{RunID: runID, OrgSlug: "acme"})

	err := acts.AppendRunLog(withWorkflowRunID(t, runID), AppendRunLogParams{
		RunID:     runID,
		OrgSlug:   "acme",
		NodeLabel: "feature_request",
		Status:    "completed",
		Iteration: 1,
		Result:    "approved",
		RoutedTo:  "ai_draft_spec",
	})
	require.NoError(t, err)

	key := "acme/runs/" + runID.String() + "/run_log.md"
	content := string(store.objects[key])
	assert.Contains(t, content, "# Run Log")
	assert.Contains(t, content, "## feature_request (completed → ai_draft_spec, iteration 1)")
}

func TestAppendRunLog_MultipleEntries(t *testing.T) {
	store := newFakeObjectStore()
	acts := &Activities{objectStoreClient: store}
	runID := uuid.New()

	acts.InitRunLog(withWorkflowRunID(t, runID), InitRunLogParams{RunID: runID})
	acts.AppendRunLog(withWorkflowRunID(t, runID), AppendRunLogParams{
		RunID: runID, NodeLabel: "A", Status: "completed", Iteration: 1, Result: "ok", RoutedTo: "B",
	})
	acts.AppendRunLog(withWorkflowRunID(t, runID), AppendRunLogParams{
		RunID: runID, NodeLabel: "B", Status: "completed", Iteration: 1, Result: "done",
	})

	key := "runs/" + runID.String() + "/run_log.md"
	content := string(store.objects[key])
	aIdx := strings.Index(content, "## A (completed")
	bIdx := strings.Index(content, "## B (completed")
	assert.Greater(t, bIdx, aIdx, "B should appear after A")
}
