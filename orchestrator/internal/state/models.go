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

// GraphRuntimeSnapshot is the projected snapshot returned by the API server.
// It contains only workflow-execution fields; infrastructure fields (image,
// secrets, namespace, docker config) are resolved by the API server.
type GraphRuntimeSnapshot struct {
	Nodes  []SnapshotNode         `json:"nodes"`
	Edges  []SnapshotEdge         `json:"edges"`
	Inputs map[string]interface{} `json:"inputs,omitempty"`
	Repos  []SnapshotRepo         `json:"repos,omitempty"`
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
	WorkflowRunID          uuid.UUID
	TemplateNodeID         uuid.UUID
	GraphVersion           int
	Iteration              int // 0 means use default (1)
	Label                  string
	IterationCapEpochStart int // 0 means "use default 1" (same convention as Iteration)
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
