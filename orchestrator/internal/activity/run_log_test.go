package activity

import (
	"context"
	"fmt"
	"strings"
	"testing"
	"unicode/utf8"

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

func TestCapRunLogField_UnderCapUnchanged(t *testing.T) {
	s := strings.Repeat("a", maxRunLogFieldBytes-1)
	assert.Equal(t, s, capRunLogField(s))
}

func TestCapRunLogField_AtCapUnchanged(t *testing.T) {
	s := strings.Repeat("a", maxRunLogFieldBytes)
	assert.Equal(t, s, capRunLogField(s))
}

func TestCapRunLogField_OverCapASCII(t *testing.T) {
	total := maxRunLogFieldBytes + 100
	s := strings.Repeat("a", total)

	result := capRunLogField(s)

	marker := fmt.Sprintf("\n… [truncated by the run log: %d of %d bytes kept]", maxRunLogFieldBytes, total)
	require.True(t, strings.HasSuffix(result, marker))
	kept := strings.TrimSuffix(result, marker)
	assert.Equal(t, maxRunLogFieldBytes, len(kept))
	assert.Equal(t, strings.Repeat("a", maxRunLogFieldBytes), kept)
}

func TestCapRunLogField_CutBeforeMultiByteRune(t *testing.T) {
	// Each rune is placed so the cap falls on every one of its continuation bytes in turn; a
	// naive byte cut would split it, so the whole rune must be dropped instead.
	for _, r := range []string{"é", "€", "😀"} {
		for straddle := 1; straddle < len(r); straddle++ {
			t.Run(fmt.Sprintf("%dbyte/cap-after-byte-%d", len(r), straddle), func(t *testing.T) {
				keptLen := maxRunLogFieldBytes - straddle
				s := strings.Repeat("a", keptLen) + r + strings.Repeat("a", 100)

				result := capRunLogField(s)

				require.True(t, utf8.ValidString(result), "result must not cut inside a UTF-8 rune")
				marker := fmt.Sprintf("\n… [truncated by the run log: %d of %d bytes kept]", keptLen, len(s))
				assert.Equal(t, strings.Repeat("a", keptLen)+marker, result)
			})
		}
	}
}

func TestBuildRunLogEntry_CapsAllThreeFields(t *testing.T) {
	overCapResult := strings.Repeat("r", maxRunLogFieldBytes+10)
	overCapError := strings.Repeat("e", maxRunLogFieldBytes+10)
	overCapRefs := strings.Repeat("f", maxRunLogFieldBytes+10)

	entry := buildRunLogEntry(AppendRunLogParams{
		NodeLabel:    "implement",
		Status:       "completed",
		Iteration:    1,
		Result:       overCapResult,
		ErrorMessage: overCapError,
		ArtifactRefs: overCapRefs,
	})

	assert.Contains(t, entry, "## implement (completed, iteration 1)")
	assert.Contains(t, entry, capRunLogField(overCapResult))
	assert.Contains(t, entry, capRunLogField(overCapError))
	assert.Contains(t, entry, capRunLogField(overCapRefs))
	assert.NotContains(t, entry, overCapResult)
	assert.NotContains(t, entry, overCapError)
	assert.NotContains(t, entry, overCapRefs)
}

func TestAppendRunLog_LargeResultBoundedGrowth(t *testing.T) {
	store := newFakeObjectStore()
	acts := &Activities{objectStoreClient: store}
	runID := uuid.New()

	acts.InitRunLog(withWorkflowRunID(t, runID), InitRunLogParams{RunID: runID})
	before := len(store.objects["runs/"+runID.String()+"/run_log.md"])

	err := acts.AppendRunLog(withWorkflowRunID(t, runID), AppendRunLogParams{
		RunID:        runID,
		NodeLabel:    "implement",
		Status:       "completed",
		Iteration:    1,
		Result:       strings.Repeat("r", 1<<20),
		ErrorMessage: strings.Repeat("e", 1<<20),
		ArtifactRefs: strings.Repeat("f", 1<<20),
	})
	require.NoError(t, err)

	after := len(store.objects["runs/"+runID.String()+"/run_log.md"])
	assert.Less(t, after-before, 3*maxRunLogFieldBytes+1024)
}

func TestAppendRunLog_DeterministicAcrossRetries(t *testing.T) {
	storeA := newFakeObjectStore()
	actsA := &Activities{objectStoreClient: storeA}
	storeB := newFakeObjectStore()
	actsB := &Activities{objectStoreClient: storeB}
	runID := uuid.New()

	params := AppendRunLogParams{
		RunID:        runID,
		NodeLabel:    "implement",
		Status:       "completed",
		Iteration:    1,
		Result:       strings.Repeat("r", maxRunLogFieldBytes+500),
		ErrorMessage: "boom",
		ArtifactRefs: `{"output":"runs/abc/out/"}`,
	}

	actsA.InitRunLog(withWorkflowRunID(t, runID), InitRunLogParams{RunID: runID})
	actsB.InitRunLog(withWorkflowRunID(t, runID), InitRunLogParams{RunID: runID})

	require.NoError(t, actsA.AppendRunLog(withWorkflowRunID(t, runID), params))
	require.NoError(t, actsB.AppendRunLog(withWorkflowRunID(t, runID), params))

	key := "runs/" + runID.String() + "/run_log.md"
	assert.Equal(t, storeA.objects[key], storeB.objects[key])
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
