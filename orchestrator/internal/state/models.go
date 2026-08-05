package state

import (
	"time"

	"github.com/google/uuid"
)

type WorkflowRun struct {
	ID              uuid.UUID
	GraphTemplateID uuid.UUID
	Status          string
	ExternalRunID   *string
	GraphVersion    int
	StartedAt       *time.Time
	CompletedAt     *time.Time
	CreatedAt       time.Time
}

type NodeExecution struct {
	ID             uuid.UUID
	WorkflowRunID  uuid.UUID
	TemplateNodeID uuid.UUID
	Status         string
	Result         *string
	ArtifactRefs   string // JSON string
	PodName        *string
	Iteration      int
	GraphVersion   int
	JobSecretHash  *string
	StartedAt      *time.Time
	CompletedAt    *time.Time
	ErrorMessage   *string
}

// SnapshotRepo holds per-repo metadata from the projected snapshot.
type SnapshotRepo struct {
	ID          string `json:"id"`
	URL         string `json:"url"`
	Name        string `json:"name"`
	TestCommand string `json:"testCommand,omitempty"`
	AgentImage  string `json:"agentImage,omitempty"`
}

// SnapshotOpenBlocker holds one of the triggering Task's own direct,
// not-yet-done incoming blocking edges. Field names are JSON-tagged to match
// OpenBlockerRef's Jackson camelCase output on the api-server side exactly —
// same cross-language sync point as SnapshotTaskContext below.
type SnapshotOpenBlocker struct {
	ItemType string    `json:"itemType"`
	ItemID   uuid.UUID `json:"itemId"`
	Title    string    `json:"title"`
	Status   string    `json:"status"`
}

// SnapshotTaskContext holds the triggering Task's identity (and its parent
// Story/Epic, when resolvable) for a task-triggered run. Absent entirely when
// the run wasn't started from a Task. Field names are JSON-tagged to match
// GraphRuntimeSnapshotResponse.TaskContext's Jackson camelCase output on the
// api-server side exactly — there is no compile-time check across this
// language boundary, so a name mismatch here silently zero-values this struct
// instead of failing loudly.
type SnapshotTaskContext struct {
	TaskID       uuid.UUID             `json:"taskId"`
	TaskTitle    string                `json:"taskTitle"`
	StoryID      *uuid.UUID            `json:"storyId,omitempty"`
	StoryTitle   *string               `json:"storyTitle,omitempty"`
	EpicID       *uuid.UUID            `json:"epicId,omitempty"`
	EpicTitle    *string               `json:"epicTitle,omitempty"`
	OpenBlockers []SnapshotOpenBlocker `json:"openBlockers,omitempty"`
}

// GraphRuntimeSnapshot is the projected snapshot returned by the API server.
// It contains only workflow-execution fields; infrastructure fields (image,
// secrets, namespace, docker config) are resolved by the API server.
type GraphRuntimeSnapshot struct {
	Nodes       []SnapshotNode         `json:"nodes"`
	Edges       []SnapshotEdge         `json:"edges"`
	Inputs      map[string]interface{} `json:"inputs,omitempty"`
	Repos       []SnapshotRepo         `json:"repos,omitempty"`
	TaskContext *SnapshotTaskContext   `json:"taskContext,omitempty"`
}

type SnapshotNode struct {
	TemplateNodeID  uuid.UUID              `json:"templateNodeId"`
	Label           string                 `json:"label"`
	ExecutorType    string                 `json:"executorType"`
	PromptTemplate  *string                `json:"promptTemplate"`
	Model           string                 `json:"model,omitempty"`
	TimeoutSeconds  int                    `json:"timeoutSeconds"`
	ConfigOverrides map[string]interface{} `json:"configOverrides"`
	IsEntrypoint    bool                   `json:"isEntrypoint"`
	OutputSpec      string                 `json:"outputSpec"`
}

type ReviewHistory struct {
	ID            uuid.UUID
	WorkflowRunID uuid.UUID
	LoopGroup     string
	Iteration     int
	ReviewerType  string
	Decision      string
	Result        string
	Status        string
	NodeLabel     string
	ArtifactRefs  string
	Timestamp     time.Time
}

type ExecutionLogEntry struct {
	ID              uuid.UUID
	NodeExecutionID uuid.UUID
	Level           string
	Message         string
	Timestamp       time.Time
}

type SnapshotEdge struct {
	TemplateEdgeID uuid.UUID `json:"templateEdgeId"`
	SourceNodeID   uuid.UUID `json:"sourceNodeId"`
	TargetNodeID   uuid.UUID `json:"targetNodeId"`
	Condition      *string   `json:"condition"`
}

// --- Param types (used by apiclient and activities) ---

type CreateNodeExecutionParams struct {
	WorkflowRunID  uuid.UUID
	TemplateNodeID uuid.UUID
	GraphVersion   int
	Iteration      int // 0 means use default (1)
	Label          string
}

type UpdateNodeExecutionParams struct {
	Status        string
	Result        *string
	ArtifactRefs  *string
	PodName       *string
	JobSecretHash *string
	ErrorMessage  *string
}

type CreateReviewHistoryParams struct {
	WorkflowRunID   uuid.UUID
	LoopGroup       string
	Iteration       int
	ReviewerType    string
	ArtifactRefs    string
	NodeExecutionID uuid.UUID
}

// PredecessorArtifacts holds a completed predecessor's info for prompt resolution
type PredecessorArtifacts struct {
	TemplateNodeID uuid.UUID
	Label          string
	ArtifactRefs   string
	Result         string
}

// InputArtifactManifest lists the files to materialise under /workspace/in/ before an agent
// starts, so it reads them off disk instead of hunting object storage for them.
type InputArtifactManifest struct {
	// Artifacts maps "<source_label>/<filename>" to its object storage path. The key doubles as
	// the path under /workspace/in/.
	Artifacts map[string]string
	// Required lists the subset of keys whose absence must fail the node rather than be skipped.
	Required []string
}
