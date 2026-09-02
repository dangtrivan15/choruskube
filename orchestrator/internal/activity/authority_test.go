package activity

import (
	"testing"

	"github.com/google/uuid"
)

func TestRunIDFromWorkflowIDParsesTheRun(t *testing.T) {
	runID := uuid.New()

	got, err := runIDFromWorkflowID("choruskube-run-" + runID.String())
	if err != nil {
		t.Fatalf("runIDFromWorkflowID = %v", err)
	}
	if got != runID {
		t.Fatalf("runID = %s, want %s", got, runID)
	}
}

func TestRunIDFromWorkflowIDRejectsAnUnexpectedShape(t *testing.T) {
	if _, err := runIDFromWorkflowID("something-else"); err == nil {
		t.Fatal("want an error for a workflow id that is not a run's")
	}
}

// The workflow id is assigned by the api-server and recorded in history; a worker answering a
// workflow task cannot change it. Comparing the params' run id against it is what stops a
// hijacked workflow task acting on another run.
func TestRequireRunMatchesRejectsAForeignRun(t *testing.T) {
	if err := requireRunMatches("choruskube-run-"+uuid.New().String(), uuid.New()); err == nil {
		t.Fatal("want an error when the params name a different run")
	}
}

func TestRequireRunMatchesAcceptsItsOwnRun(t *testing.T) {
	runID := uuid.New()

	if err := requireRunMatches("choruskube-run-"+runID.String(), runID); err != nil {
		t.Fatalf("requireRunMatches = %v, want nil", err)
	}
}
