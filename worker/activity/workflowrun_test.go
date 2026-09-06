package activity

import (
	"context"
	"testing"

	"github.com/google/uuid"
	temporalactivity "go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/workflow"
)

func TestRunIDFromWorkflowID(t *testing.T) {
	id := uuid.New()
	got, err := runIDFromWorkflowID("choruskube-run-" + id.String())
	if err != nil || got != id {
		t.Fatalf("got (%v, %v), want (%v, nil)", got, err, id)
	}
	if _, err := runIDFromWorkflowID("something-else"); err == nil {
		t.Fatal("a workflow id with no run prefix must be an error, not a zero uuid")
	}
	if _, err := runIDFromWorkflowID("choruskube-run-not-a-uuid"); err == nil {
		t.Fatal("a malformed run id must be an error")
	}
}

func TestRunIDOfReadsTheActivityContext(t *testing.T) {
	id := uuid.New()
	original := activityInfo
	activityInfo = func(context.Context) temporalactivity.Info {
		return temporalactivity.Info{WorkflowExecution: workflow.Execution{ID: "choruskube-run-" + id.String()}}
	}
	defer func() { activityInfo = original }()

	got, err := runIDOf(context.Background())
	if err != nil || got != id {
		t.Fatalf("got (%v, %v), want (%v, nil)", got, err, id)
	}
}

func TestRunIDOfExportedWrapper(t *testing.T) {
	id := uuid.New()
	original := activityInfo
	activityInfo = func(context.Context) temporalactivity.Info {
		return temporalactivity.Info{WorkflowExecution: workflow.Execution{ID: "choruskube-run-" + id.String()}}
	}
	defer func() { activityInfo = original }()

	got, err := RunIDOf(context.Background())
	if err != nil || got != id {
		t.Fatalf("got (%v, %v), want (%v, nil)", got, err, id)
	}
}
