package activity

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"

	"github.com/google/uuid"
	"go.temporal.io/sdk/activity"

	"github.com/dangtrivan15/choruskube/worker/workload"
)

// newTestActivities starts a stub API server that accepts CreateWorkload POSTs, capturing the
// last request's configJson into *captured, and returns Activities wired to call it.
func newTestActivities(t *testing.T, captured *map[string]interface{}) *Activities {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/internal/workloads/") {
			var req map[string]interface{}
			_ = json.NewDecoder(r.Body).Decode(&req)
			if cj, ok := req["configJson"].(map[string]interface{}); ok {
				*captured = cj
			}
			w.WriteHeader(http.StatusCreated)
			_ = json.NewEncoder(w).Encode(map[string]interface{}{
				"executionHandle": "agent-abc12345",
				"jobSecretHash":   "hash123",
			})
			return
		}
		w.WriteHeader(http.StatusOK)
	}))
	t.Cleanup(srv.Close)

	acts := New(workload.NewClient(srv.URL, "test-secret", srv.Client()))
	acts.CallbackURL = "http://callback:9090/api/v1/callback"
	acts.APIServerURL = srv.URL
	return acts
}

func requirePending(t *testing.T, err error) {
	t.Helper()
	if !errors.Is(err, activity.ErrResultPending) {
		t.Fatalf("want activity.ErrResultPending, got %v", err)
	}
}

func TestExecuteAINodeFromSnapshot_OutputPathKeyedByExecutionID(t *testing.T) {
	execID := uuid.New()
	runID := uuid.New()
	templateNodeID := uuid.New()

	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: execID,
		RunID:           runID,
		TemplateNodeID:  templateNodeID,
		Label:           "spec_review",
		ExecutorType:    "ai",
		PromptTemplate:  "irrelevant",
		Iteration:       2,
	})
	requirePending(t, err)

	outputPath, _ := receivedConfigJSON["output_path"].(string)
	if !strings.Contains(outputPath, execID.String()) {
		t.Fatalf("output_path must include the execution ID so iterations own their own prefix: %q", outputPath)
	}
	if strings.Contains(outputPath, templateNodeID.String()) {
		t.Fatalf("output_path must NOT be keyed by template node ID — that causes iterations to overwrite each other: %q", outputPath)
	}
}

func TestExecuteAINodeFromSnapshot_ScriptNode_ConfigJson(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "test",
		ExecutorType:    "script",
		Command:         "cd /workspace/repo && npm test",
		RepoURL:         "https://github.com/test/repo",
		WorkingBranch:   "choruskube-run-abc123",
		PromptTemplate:  "", // script nodes have no prompt
		Variables:       map[string]string{"run.id": "abc123"},
		Iteration:       1,
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	if receivedConfigJSON["executor_type"] != "script" {
		t.Fatalf("executor_type = %v, want script", receivedConfigJSON["executor_type"])
	}
	if receivedConfigJSON["command"] != "cd /workspace/repo && npm test" {
		t.Fatalf("command = %v", receivedConfigJSON["command"])
	}
	if receivedConfigJSON["repo_url"] != "https://github.com/test/repo" {
		t.Fatalf("repo_url = %v", receivedConfigJSON["repo_url"])
	}
	if receivedConfigJSON["working_branch"] != "choruskube-run-abc123" {
		t.Fatalf("working_branch = %v", receivedConfigJSON["working_branch"])
	}
	if receivedConfigJSON["prompt"] != "" {
		t.Fatalf("prompt = %v, want empty", receivedConfigJSON["prompt"])
	}

	// Object storage fields should NOT be in config.json
	if receivedConfigJSON["minio_endpoint"] != nil || receivedConfigJSON["minio_bucket"] != nil {
		t.Fatalf("object storage fields leaked into config.json: %v", receivedConfigJSON)
	}
}

func TestExecuteAINodeFromSnapshot_NoSystemPromptInConfigJson(t *testing.T) {
	runID := uuid.New()

	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           runID,
		TemplateNodeID:  uuid.New(),
		Label:           "implement",
		ExecutorType:    "ai",
		PromptTemplate:  "Do the thing",
		Variables:       map[string]string{"run.id": runID.String()},
		Iteration:       1,
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	// System prompt is built by the entrypoint from the image-local template, not passed
	// through config.json.
	if _, hasSystemPrompt := receivedConfigJSON["system_prompt"]; hasSystemPrompt {
		t.Fatal("system_prompt should not be in config.json")
	}
	want := "runs/" + runID.String() + "/run_log.md"
	if receivedConfigJSON["run_log_path"] != want {
		t.Fatalf("run_log_path = %v, want %v", receivedConfigJSON["run_log_path"], want)
	}
}

func TestExecuteAINodeFromSnapshot_TaskContextInConfigJson(t *testing.T) {
	runID := uuid.New()
	taskID := uuid.New()
	storyID := uuid.New()
	epicID := uuid.New()

	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           runID,
		TemplateNodeID:  uuid.New(),
		Label:           "implement",
		ExecutorType:    "ai",
		PromptTemplate:  "Do the thing",
		Variables:       map[string]string{"run.id": runID.String()},
		TaskID:          taskID.String(),
		TaskTitle:       "Wire up task_context",
		StoryID:         storyID.String(),
		StoryTitle:      "Agent identity threading",
		EpicID:          epicID.String(),
		EpicTitle:       "Roadmap-aware agents",
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	taskContext, ok := receivedConfigJSON["task_context"].(map[string]interface{})
	if !ok {
		t.Fatal("task_context should be present in config.json when TaskID is set")
	}
	checks := map[string]string{
		"task_id":     taskID.String(),
		"task_title":  "Wire up task_context",
		"story_id":    storyID.String(),
		"story_title": "Agent identity threading",
		"epic_id":     epicID.String(),
		"epic_title":  "Roadmap-aware agents",
	}
	for key, want := range checks {
		if taskContext[key] != want {
			t.Fatalf("task_context[%q] = %v, want %v", key, taskContext[key], want)
		}
	}
}

func TestExecuteAINodeFromSnapshot_NoTaskContextWhenTaskIDEmpty(t *testing.T) {
	runID := uuid.New()

	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	// Manually-started run: no TaskID set at all.
	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           runID,
		TemplateNodeID:  uuid.New(),
		Label:           "implement",
		ExecutorType:    "ai",
		PromptTemplate:  "Do the thing",
		Variables:       map[string]string{"run.id": runID.String()},
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	if _, hasTaskContext := receivedConfigJSON["task_context"]; hasTaskContext {
		t.Fatal("task_context should be absent from config.json when TaskID is empty")
	}
}

func TestExecuteAINodeFromSnapshot_TaskContextIncludesOpenBlockers(t *testing.T) {
	runID := uuid.New()
	taskID := uuid.New()
	blockerID := uuid.New()

	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           runID,
		TemplateNodeID:  uuid.New(),
		Label:           "implement",
		ExecutorType:    "ai",
		PromptTemplate:  "Do the thing",
		Variables:       map[string]string{"run.id": runID.String()},
		TaskID:          taskID.String(),
		TaskTitle:       "Wire up open blockers",
		OpenBlockers: []OpenBlockerParam{
			{ItemType: "task", ItemID: blockerID.String(), Title: "Prerequisite Task", Status: "in_progress"},
		},
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	taskContext, ok := receivedConfigJSON["task_context"].(map[string]interface{})
	if !ok {
		t.Fatal("task_context should be present in config.json when TaskID is set")
	}
	openBlockers, ok := taskContext["open_blockers"].([]interface{})
	if !ok || len(openBlockers) != 1 {
		t.Fatalf("open_blockers should have exactly one entry, got %v", taskContext["open_blockers"])
	}
	blocker, ok := openBlockers[0].(map[string]interface{})
	if !ok {
		t.Fatalf("open_blockers[0] has unexpected shape: %v", openBlockers[0])
	}
	if blocker["item_type"] != "task" || blocker["item_id"] != blockerID.String() ||
		blocker["title"] != "Prerequisite Task" || blocker["status"] != "in_progress" {
		t.Fatalf("unexpected blocker contents: %v", blocker)
	}
}

func TestExecuteAINodeFromSnapshot_NoOpenBlockersKeyWhenEmpty(t *testing.T) {
	runID := uuid.New()
	taskID := uuid.New()

	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	// TaskID set (task_context present) but OpenBlockers left empty — the run's Task has no
	// open blockers today.
	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           runID,
		TemplateNodeID:  uuid.New(),
		Label:           "implement",
		ExecutorType:    "ai",
		PromptTemplate:  "Do the thing",
		Variables:       map[string]string{"run.id": runID.String()},
		TaskID:          taskID.String(),
		TaskTitle:       "No blockers here",
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	taskContext, ok := receivedConfigJSON["task_context"].(map[string]interface{})
	if !ok {
		t.Fatal("task_context should be present in config.json when TaskID is set")
	}
	if _, hasOpenBlockers := taskContext["open_blockers"]; hasOpenBlockers {
		t.Fatal("open_blockers key should be absent from task_context when OpenBlockers is empty")
	}
}

func TestExecuteAINodeFromSnapshot_IterationInConfigJson(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "retry-node",
		ExecutorType:    "script",
		Command:         "echo hello",
		PromptTemplate:  "",
		Variables:       map[string]string{"run.id": "test123"},
		Iteration:       3,
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	if receivedConfigJSON["iteration"] != float64(3) {
		t.Fatalf("iteration = %v, want 3", receivedConfigJSON["iteration"])
	}
	// Regression guard: the iteration-cap epoch machinery was removed; make sure
	// it doesn't silently reappear in the agent's config.json.
	if _, hasEpochKey := receivedConfigJSON["iteration_in_epoch"]; hasEpochKey {
		t.Fatal("iteration_in_epoch should not be present in config.json")
	}
}

func TestExecuteAINodeFromSnapshot_IterationZeroOmitted(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "first-run",
		ExecutorType:    "ai",
		PromptTemplate:  "Do something",
		Variables:       map[string]string{"run.id": "test123"},
		Iteration:       0,
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	if _, exists := receivedConfigJSON["iteration"]; exists {
		t.Fatal("iteration=0 should not be in config.json")
	}
}

func TestExecuteAINodeFromSnapshot_PredecessorArtifactAnnotation(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "implement",
		ExecutorType:    "ai",
		PromptTemplate:  "Do the thing",
		Variables: map[string]string{
			"run.id":                "abc123",
			"input.gate.result":     "approved",          // .result entry — must be excluded
			"input.gate.file.png":   "orgs/x/gate.png",   // artifact — must be included
			"input.gate.report.pdf": "orgs/x/report.pdf", // artifact — must be included
		},
		Iteration: 1,
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	prompt, ok := receivedConfigJSON["prompt"].(string)
	if !ok {
		t.Fatal("prompt should be a string in config.json")
	}
	if !strings.Contains(prompt, "**Predecessor Artifacts**") {
		t.Fatal("prompt should contain the annotation header")
	}
	if !strings.Contains(prompt, "input.gate.file.png") || !strings.Contains(prompt, "input.gate.report.pdf") {
		t.Fatalf("artifact keys should appear in annotation: %q", prompt)
	}
	if strings.Contains(prompt, "input.gate.result") {
		t.Fatalf("result key must not appear in annotation: %q", prompt)
	}
}

// TestExecuteAINodeFromSnapshot_OnlyResultEntries_NoAnnotation verifies that when all
// input.* variables end in ".result", no artifact annotation block is appended.
func TestExecuteAINodeFromSnapshot_OnlyResultEntries_NoAnnotation(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "implement",
		ExecutorType:    "ai",
		PromptTemplate:  "Do the thing",
		Variables: map[string]string{
			"run.id":            "abc123",
			"input.gate.result": "approved", // only .result entries — no annotation
		},
		Iteration: 1,
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	prompt, ok := receivedConfigJSON["prompt"].(string)
	if !ok {
		t.Fatal("prompt should be a string in config.json")
	}
	if strings.Contains(prompt, "**Predecessor Artifacts**") {
		t.Fatal("no annotation block expected when only .result entries present")
	}
}

// TestExecuteAINodeFromSnapshot_PredecessorArtifactAnnotation_MaterialisedFiltered pins the
// interaction between the two maps that describe the same predecessor files: Variables drives
// the prompt suffix (keyed "input.{label}.{filename}") while InputArtifacts drives what the
// entrypoint downloads to /workspace/in/{label}/{filename} before the agent starts. A file in
// the latter is already on disk, so advertising it as something to `artifact get` is wrong —
// it must be filtered out, matching on the translated key rather than the raw string.
func TestExecuteAINodeFromSnapshot_PredecessorArtifactAnnotation_MaterialisedFiltered(t *testing.T) {
	tests := []struct {
		name           string
		variables      map[string]string
		inputArtifacts map[string]string
		wantBlock      bool
		wantPresent    []string
		wantAbsent     []string
	}{
		{
			name: "materialised entry is omitted, un-materialised sibling stays",
			variables: map[string]string{
				"input.spec_review.result":           "approved",
				"input.spec_review.spec_and_plan.md": "system/runs/abc/out/spec_and_plan.md",
				"input.spec_review.notes.md":         "system/runs/abc/out/notes.md",
			},
			inputArtifacts: map[string]string{
				"spec_review/spec_and_plan.md": "system/runs/abc/out/spec_and_plan.md",
			},
			wantBlock:   true,
			wantPresent: []string{"input.spec_review.notes.md", "system/runs/abc/out/notes.md"},
			wantAbsent:  []string{"input.spec_review.spec_and_plan.md"},
		},
		{
			name: "un-materialised entry still appears when nothing was downloaded",
			variables: map[string]string{
				"input.gate.human_guidance.md": "system/runs/abc/gate/human_guidance.md",
			},
			inputArtifacts: map[string]string{},
			wantBlock:      true,
			wantPresent:    []string{"input.gate.human_guidance.md"},
		},
		{
			name: "same filename under a different label is not treated as materialised",
			variables: map[string]string{
				"input.gate.human_guidance.md": "system/runs/abc/gate/human_guidance.md",
			},
			inputArtifacts: map[string]string{
				"older_gate/human_guidance.md": "system/runs/abc/older-gate/human_guidance.md",
			},
			wantBlock:   true,
			wantPresent: []string{"input.gate.human_guidance.md"},
		},
		{
			name: "fully materialised set produces no suffix at all",
			variables: map[string]string{
				"input.spec_review.result":           "approved",
				"input.spec_review.spec_and_plan.md": "system/runs/abc/out/spec_and_plan.md",
				"input.gate.human_guidance.md":       "system/runs/abc/gate/human_guidance.md",
			},
			inputArtifacts: map[string]string{
				"spec_review/spec_and_plan.md": "system/runs/abc/out/spec_and_plan.md",
				"gate/human_guidance.md":       "system/runs/abc/gate/human_guidance.md",
			},
			wantBlock:  false,
			wantAbsent: []string{"input.spec_review.spec_and_plan.md", "input.gate.human_guidance.md"},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var receivedConfigJSON map[string]interface{}
			acts := newTestActivities(t, &receivedConfigJSON)

			_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
				NodeExecutionID: uuid.New(),
				RunID:           uuid.New(),
				TemplateNodeID:  uuid.New(),
				Label:           "implement",
				ExecutorType:    "ai",
				PromptTemplate:  "Do the thing",
				Variables:       tt.variables,
				InputArtifacts:  tt.inputArtifacts,
				Iteration:       1,
			})
			requirePending(t, err)

			resolvedPrompt, ok := receivedConfigJSON["prompt"].(string)
			if !ok {
				t.Fatal("prompt should be a string in config.json")
			}

			hasBlock := strings.Contains(resolvedPrompt, "**Predecessor Artifacts**")
			if hasBlock != tt.wantBlock {
				t.Fatalf("annotation block present = %v, want %v", hasBlock, tt.wantBlock)
			}
			for _, want := range tt.wantPresent {
				if !strings.Contains(resolvedPrompt, want) {
					t.Fatalf("expected entry %q missing from annotation: %q", want, resolvedPrompt)
				}
			}
			for _, notWant := range tt.wantAbsent {
				if strings.Contains(resolvedPrompt, notWant) {
					t.Fatalf("materialised file %q must not be advertised as a manual download: %q", notWant, resolvedPrompt)
				}
			}
		})
	}
}

// TestExecuteAINodeFromSnapshot_RunInputAnnotation verifies that run-level attachments
// (keys prefixed "run_input/" in InputArtifacts) are announced to the LLM in a
// dedicated "Run Inputs" block, matching the predecessor-artifact behavior. Without
// this announcement the LLM has no way to learn about user-uploaded files —
// entrypoint.sh downloads them silently and the prompt would not mention them.
func TestExecuteAINodeFromSnapshot_RunInputAnnotation(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "implement",
		ExecutorType:    "ai",
		PromptTemplate:  "Do the thing",
		InputArtifacts: map[string]string{
			"run_input/mockup.png": "system/runs/abc/inputs/mockup.png",  // run-level — must appear
			"run_input/spec.md":    "system/runs/abc/inputs/spec.md",     // run-level — must appear
			"input/gate/feedback":  "system/runs/abc/gate-attachments/x", // not run_input/ — must NOT appear in Run Inputs block
		},
		Iteration: 1,
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	prompt, ok := receivedConfigJSON["prompt"].(string)
	if !ok {
		t.Fatal("prompt should be a string in config.json")
	}
	if !strings.Contains(prompt, "**Run Inputs**") {
		t.Fatal("prompt should contain the Run Inputs annotation header")
	}
	if !strings.Contains(prompt, "run_input/mockup.png") || !strings.Contains(prompt, "run_input/spec.md") {
		t.Fatalf("run-level input keys should appear in annotation: %q", prompt)
	}
	if !strings.Contains(prompt, "system/runs/abc/inputs/mockup.png") {
		t.Fatalf("run-level object storage path should appear in annotation: %q", prompt)
	}

	// Lines should be sorted (deterministic output).
	mockupIdx := strings.Index(prompt, "run_input/mockup.png")
	specIdx := strings.Index(prompt, "run_input/spec.md")
	if mockupIdx <= 0 || specIdx <= 0 || mockupIdx >= specIdx {
		t.Fatalf("annotation lines should be sorted alphabetically: mockupIdx=%d specIdx=%d", mockupIdx, specIdx)
	}

	// The non-run_input key must not be hoisted into the Run Inputs block, and no
	// Predecessor Artifacts block should appear since Variables has no input.* keys.
	if strings.Contains(prompt, "**Predecessor Artifacts**") {
		t.Fatal("no predecessor block expected when Variables has no input.* keys")
	}
	if strings.Contains(prompt, "input/gate/feedback") {
		t.Fatal("non-run_input/ keys must not be announced")
	}
}

// TestExecuteAINodeFromSnapshot_NoRunInputs_NoAnnotation verifies that when no
// "run_input/" keys are present, the Run Inputs block is not added.
func TestExecuteAINodeFromSnapshot_NoRunInputs_NoAnnotation(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "implement",
		ExecutorType:    "ai",
		PromptTemplate:  "Do the thing",
		InputArtifacts:  map[string]string{}, // empty — nothing to announce
		Iteration:       1,
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	prompt, ok := receivedConfigJSON["prompt"].(string)
	if !ok {
		t.Fatal("prompt should be a string in config.json")
	}
	if strings.Contains(prompt, "**Run Inputs**") {
		t.Fatal("no Run Inputs block expected when InputArtifacts has no run_input/ keys")
	}
}

// TestExecuteAINodeFromSnapshot_OutputSpec_Present verifies that when OutputSpec is a
// non-empty, non-"{}" JSON string, it is forwarded as "output_spec" in config.json.
func TestExecuteAINodeFromSnapshot_OutputSpec_Present(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	outputSpec := `{"files":[{"name":"report.pdf","required":true}]}`
	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "generate-report",
		ExecutorType:    "ai",
		PromptTemplate:  "Generate a report",
		Variables:       map[string]string{"run.id": "abc123"},
		Iteration:       1,
		OutputSpec:      outputSpec,
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	if receivedConfigJSON["output_spec"] != outputSpec {
		t.Fatalf("output_spec = %v, want forwarded verbatim", receivedConfigJSON["output_spec"])
	}
}

// TestExecuteAINodeFromSnapshot_OutputSpec_Empty verifies that when OutputSpec is empty,
// "output_spec" is absent from config.json.
func TestExecuteAINodeFromSnapshot_OutputSpec_Empty(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "simple-node",
		ExecutorType:    "ai",
		PromptTemplate:  "Do something",
		Variables:       map[string]string{"run.id": "abc123"},
		Iteration:       1,
		OutputSpec:      "", // empty — should not appear in config.json
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	if _, exists := receivedConfigJSON["output_spec"]; exists {
		t.Fatal("output_spec should be absent from config.json when OutputSpec is empty")
	}
}

// TestExecuteAINodeFromSnapshot_OutputSpec_EmptyObject verifies that when OutputSpec is "{}",
// "output_spec" is absent from config.json (treated the same as empty).
func TestExecuteAINodeFromSnapshot_OutputSpec_EmptyObject(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "simple-node",
		ExecutorType:    "ai",
		PromptTemplate:  "Do something",
		Variables:       map[string]string{"run.id": "abc123"},
		Iteration:       1,
		OutputSpec:      "{}", // empty object — should not appear in config.json
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	if _, exists := receivedConfigJSON["output_spec"]; exists {
		t.Fatal("output_spec should be absent from config.json when OutputSpec is \"{}\"")
	}
}

// TestConfigJSON_SupervisorEmittedOnlyWhenDeclared verifies that config.json carries a
// "supervisor" key exactly when SupervisorLabel is set, and that the key is absent
// entirely — not present-but-empty — when it isn't, so an older template's config.json
// keeps its exact current shape.
func TestConfigJSON_SupervisorEmittedOnlyWhenDeclared(t *testing.T) {
	tests := []struct {
		name            string
		supervisorLabel string
		wantPresent     bool
	}{
		{name: "label set — supervisor key present with label and name", supervisorLabel: "qa-lead", wantPresent: true},
		{name: "label empty — supervisor key absent entirely", supervisorLabel: "", wantPresent: false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var receivedConfigJSON map[string]interface{}
			acts := newTestActivities(t, &receivedConfigJSON)

			params := ExecuteAINodeFromSnapshotParams{
				NodeExecutionID: uuid.New(),
				RunID:           uuid.New(),
				TemplateNodeID:  uuid.New(),
				Label:           "implement",
				ExecutorType:    "ai",
				PromptTemplate:  "Do the thing",
				Variables:       map[string]string{"run.id": "abc123"},
				Iteration:       1,
				SupervisorLabel: tt.supervisorLabel,
			}

			_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
			requirePending(t, err)

			supervisor, exists := receivedConfigJSON["supervisor"]
			if !tt.wantPresent {
				if exists {
					t.Fatal("supervisor key should be absent from config.json when SupervisorLabel is empty")
				}
				return
			}
			if !exists {
				t.Fatal("supervisor key should be present in config.json when SupervisorLabel is set")
			}
			want := map[string]interface{}{"label": tt.supervisorLabel, "name": "Supervisor"}
			if !reflect.DeepEqual(supervisor, want) {
				t.Fatalf("supervisor = %v, want %v", supervisor, want)
			}
		})
	}
}

// TestExecuteAINodeFromSnapshot_ModelInConfigJson verifies that NodeDefinition.model
// is propagated to the agent via config.json["model"] when set.
func TestExecuteAINodeFromSnapshot_ModelInConfigJson(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "push_pr",
		ExecutorType:    "ai",
		PromptTemplate:  "irrelevant",
		Model:           "claude-haiku-4-5-20251001",
	})
	requirePending(t, err)

	if receivedConfigJSON["model"] != "claude-haiku-4-5-20251001" {
		t.Fatalf("config.json must include model when NodeDefinition.model is set, got %v", receivedConfigJSON["model"])
	}
}

// TestExecuteAINodeFromSnapshot_ModelOmittedWhenEmpty verifies model is omitted from
// config.json when not set (so the agent falls back to its default model).
func TestExecuteAINodeFromSnapshot_ModelOmittedWhenEmpty(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "spec_review",
		ExecutorType:    "ai",
		PromptTemplate:  "irrelevant",
		// Model intentionally not set
	})
	requirePending(t, err)

	if _, hasModel := receivedConfigJSON["model"]; hasModel {
		t.Fatal("config.json must omit model when not set on the snapshot")
	}
}

// TestExecuteAINodeFromSnapshot_EffortInConfigJson verifies that the effort override
// extracted from config_overrides is propagated to the agent via config.json["effort"]
// when set.
func TestExecuteAINodeFromSnapshot_EffortInConfigJson(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "code_review",
		ExecutorType:    "ai",
		PromptTemplate:  "irrelevant",
		Effort:          "xhigh",
	})
	requirePending(t, err)

	if receivedConfigJSON["effort"] != "xhigh" {
		t.Fatalf("config.json must include effort when set on the snapshot, got %v", receivedConfigJSON["effort"])
	}
}

// TestExecuteAINodeFromSnapshot_EffortOmittedWhenEmpty verifies effort is omitted from
// config.json when not set (not present as an empty string).
func TestExecuteAINodeFromSnapshot_EffortOmittedWhenEmpty(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "spec_review",
		ExecutorType:    "ai",
		PromptTemplate:  "irrelevant",
		// Effort intentionally not set
	})
	requirePending(t, err)

	if _, hasEffort := receivedConfigJSON["effort"]; hasEffort {
		t.Fatal("config.json must omit effort when not set on the snapshot")
	}
}

// TestExecuteAINodeFromSnapshot_ResolvedModelEffortReachConfigJSONUnchanged_BothIterationBands
// covers the spec's Integration testing bullet for the per-node-type model/effort feature:
// dag_executor.go resolves the four new iteration-aware config_overrides keys
// (model_first_iteration/model_subsequent_iteration/effort_first_iteration/
// effort_subsequent_iteration) down to a single concrete Model/Effort pair BEFORE calling this
// activity — this activity itself is unchanged and only ever sees that resolved pair via
// ExecuteAINodeFromSnapshotParams.Model/.Effort, never the raw iteration-suffixed keys. This
// test simulates both bands the DAG executor can hand it (a first-iteration resolution and a
// subsequent-iteration one) and confirms each reaches config.json verbatim as plain
// "model"/"effort" keys, proving the pass-through stays generic across both.
func TestExecuteAINodeFromSnapshot_ResolvedModelEffortReachConfigJSONUnchanged_BothIterationBands(t *testing.T) {
	// Band 1: as dag_executor.go resolves it on tracker.reviewPass == 1 (from
	// model_first_iteration/effort_first_iteration).
	var firstIterationConfigJSON map[string]interface{}
	firstActs := newTestActivities(t, &firstIterationConfigJSON)
	_, err := firstActs.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "code_review",
		ExecutorType:    "ai",
		PromptTemplate:  "irrelevant",
		Model:           "opus-x",
		Effort:          "xhigh",
		Iteration:       1,
	})
	requirePending(t, err)
	if firstIterationConfigJSON["model"] != "opus-x" || firstIterationConfigJSON["effort"] != "xhigh" {
		t.Fatalf("config.json must carry the resolved first-iteration model/effort unchanged: %v", firstIterationConfigJSON)
	}
	if _, ok := firstIterationConfigJSON["model_first_iteration"]; ok {
		t.Fatal("config.json must never carry the raw iteration-suffixed key")
	}
	if _, ok := firstIterationConfigJSON["model_subsequent_iteration"]; ok {
		t.Fatal("config.json must never carry the raw iteration-suffixed key")
	}

	// Band 2: as dag_executor.go resolves it on tracker.reviewPass > 1 (from
	// model_subsequent_iteration/effort_subsequent_iteration).
	var subsequentIterationConfigJSON map[string]interface{}
	subsequentActs := newTestActivities(t, &subsequentIterationConfigJSON)
	_, err = subsequentActs.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "code_review",
		ExecutorType:    "ai",
		PromptTemplate:  "irrelevant",
		Model:           "sonnet-y",
		Effort:          "high",
		Iteration:       2,
	})
	requirePending(t, err)
	if subsequentIterationConfigJSON["model"] != "sonnet-y" || subsequentIterationConfigJSON["effort"] != "high" {
		t.Fatalf("config.json must carry the resolved subsequent-iteration model/effort unchanged: %v", subsequentIterationConfigJSON)
	}
	if _, ok := subsequentIterationConfigJSON["effort_first_iteration"]; ok {
		t.Fatal("config.json must never carry the raw iteration-suffixed key")
	}
	if _, ok := subsequentIterationConfigJSON["effort_subsequent_iteration"]; ok {
		t.Fatal("config.json must never carry the raw iteration-suffixed key")
	}
}

// TestExecuteAINodeFromSnapshot_TurnBudgetInConfigJson verifies that the per-node
// max_turns/max_retries overrides extracted from config_overrides reach the agent via
// config.json when set.
func TestExecuteAINodeFromSnapshot_TurnBudgetInConfigJson(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "implement",
		ExecutorType:    "ai",
		PromptTemplate:  "irrelevant",
		MaxTurns:        "250",
		MaxRetries:      "5",
	})
	requirePending(t, err)

	if receivedConfigJSON["max_turns"] != "250" {
		t.Fatalf("max_turns = %v, want 250", receivedConfigJSON["max_turns"])
	}
	if receivedConfigJSON["max_retries"] != "5" {
		t.Fatalf("max_retries = %v, want 5", receivedConfigJSON["max_retries"])
	}
}

// TestExecuteAINodeFromSnapshot_TurnBudgetOmittedWhenEmpty verifies max_turns/max_retries
// are absent from config.json when unset — not present as empty strings, which the agent
// would have to special-case instead of simply falling back to its own defaults. The two
// default independently, so this also covers one being set without the other.
func TestExecuteAINodeFromSnapshot_TurnBudgetOmittedWhenEmpty(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "spec_review",
		ExecutorType:    "ai",
		PromptTemplate:  "irrelevant",
		MaxTurns:        "150",
		// MaxRetries intentionally not set
	})
	requirePending(t, err)

	if receivedConfigJSON["max_turns"] != "150" {
		t.Fatalf("max_turns = %v, want 150", receivedConfigJSON["max_turns"])
	}
	if _, hasMaxRetries := receivedConfigJSON["max_retries"]; hasMaxRetries {
		t.Fatal("config.json must omit max_retries when not set on the snapshot")
	}

	receivedConfigJSON = nil
	_, err = acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "spec_review",
		ExecutorType:    "ai",
		PromptTemplate:  "irrelevant",
		// Neither MaxTurns nor MaxRetries set
	})
	requirePending(t, err)

	if _, hasMaxTurns := receivedConfigJSON["max_turns"]; hasMaxTurns {
		t.Fatal("config.json must omit max_turns when not set on the snapshot")
	}
	if _, hasMaxRetries := receivedConfigJSON["max_retries"]; hasMaxRetries {
		t.Fatal("config.json must omit max_retries when not set on the snapshot")
	}
}

// TestExecuteAINodeFromSnapshot_SessionInConfigJson verifies that a session parked by a
// previous iteration (SessionID/SessionArtifactPath set by the workflow's rate-limited
// re-queue) reaches the agent via config.json's session_id/session_artifact_path — the
// exact keys the entrypoint reads to resume instead of starting a fresh session.
func TestExecuteAINodeFromSnapshot_SessionInConfigJson(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		NodeExecutionID:     uuid.New(),
		RunID:               uuid.New(),
		TemplateNodeID:      uuid.New(),
		Label:               "implement",
		ExecutorType:        "ai",
		PromptTemplate:      "irrelevant",
		SessionID:           "sess-1",
		SessionArtifactPath: "runs/r/e/session/sess-1.jsonl",
	})
	requirePending(t, err)

	if receivedConfigJSON["session_id"] != "sess-1" {
		t.Fatalf("session_id = %v, want sess-1", receivedConfigJSON["session_id"])
	}
	if receivedConfigJSON["session_artifact_path"] != "runs/r/e/session/sess-1.jsonl" {
		t.Fatalf("session_artifact_path = %v", receivedConfigJSON["session_artifact_path"])
	}
}

// TestExecuteAINodeFromSnapshot_SessionOmittedWhenEmpty verifies session_id/
// session_artifact_path are absent from config.json for an ordinary iteration that
// resumes no parked session — not present as empty strings, which the agent would have
// to special-case instead of simply starting a fresh session.
func TestExecuteAINodeFromSnapshot_SessionOmittedWhenEmpty(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "implement",
		ExecutorType:    "ai",
		PromptTemplate:  "irrelevant",
		// SessionID intentionally not set
	})
	requirePending(t, err)

	if _, hasSessionID := receivedConfigJSON["session_id"]; hasSessionID {
		t.Fatal("config.json must omit session_id when the iteration resumes no parked session")
	}
	if _, hasSessionArtifactPath := receivedConfigJSON["session_artifact_path"]; hasSessionArtifactPath {
		t.Fatal("config.json must omit session_artifact_path when the iteration resumes no parked session")
	}
}

// TestExecuteAINodeFromSnapshot_NeedsPRInConfigJson verifies that NeedsPR true (derived
// from config_overrides.needs_pr == "true") is propagated to the agent via
// config.json["needs_pr"].
func TestExecuteAINodeFromSnapshot_NeedsPRInConfigJson(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "implement",
		ExecutorType:    "ai",
		PromptTemplate:  "irrelevant",
		NeedsPR:         true,
	})
	requirePending(t, err)

	if receivedConfigJSON["needs_pr"] != true {
		t.Fatalf("config.json must include needs_pr=true when NeedsPR is set on the snapshot, got %v", receivedConfigJSON["needs_pr"])
	}
}

// TestExecuteAINodeFromSnapshot_NeedsPROmittedWhenFalse verifies needs_pr is omitted from
// config.json when NeedsPR is false (the zero value, i.e. not set via config_overrides).
func TestExecuteAINodeFromSnapshot_NeedsPROmittedWhenFalse(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "spec_review",
		ExecutorType:    "ai",
		PromptTemplate:  "irrelevant",
		// NeedsPR intentionally not set
	})
	requirePending(t, err)

	if _, hasNeedsPR := receivedConfigJSON["needs_pr"]; hasNeedsPR {
		t.Fatal("config.json must omit needs_pr when not set on the snapshot")
	}
}

func TestFetchPodLogs_DelegatesToAPIServer(t *testing.T) {
	execID := uuid.New()

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"logs": "(no pod found)",
		})
	}))
	defer srv.Close()

	acts := New(workload.NewClient(srv.URL, "test-secret", srv.Client()))

	logs, err := acts.FetchPodLogs(context.Background(), FetchPodLogsParams{
		NodeExecutionID: execID,
		TailLines:       50,
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if logs != "(no pod found)" {
		t.Fatalf("logs = %q, want %q", logs, "(no pod found)")
	}
}

func TestDeleteAgentJob_DelegatesToAPIServer(t *testing.T) {
	execID := uuid.New()
	var gotMethod, gotPath string

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod, gotPath = r.Method, r.URL.Path
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()

	acts := New(workload.NewClient(srv.URL, "test-secret", srv.Client()))

	if err := acts.DeleteAgentJob(context.Background(), DeleteAgentJobParams{NodeExecutionID: execID}); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if gotMethod != http.MethodDelete {
		t.Fatalf("method = %q, want DELETE", gotMethod)
	}
	if gotPath != "/internal/workloads/"+execID.String() {
		t.Fatalf("path = %q", gotPath)
	}
}

// TestParamsHasNoRunLogPath pins the deletion of the deprecated RunLogPath field: the
// activity body reads it zero times, so a reintroduction would be pure replay ballast.
func TestParamsHasNoRunLogPath(t *testing.T) {
	if _, ok := reflect.TypeOf(ExecuteAINodeFromSnapshotParams{}).FieldByName("RunLogPath"); ok {
		t.Fatal("RunLogPath was reintroduced: it is replay ballast the activity never reads")
	}
}
