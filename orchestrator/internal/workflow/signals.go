package workflow

// Signal channel names — must match the Java API server's untyped signal calls.
// Human decision uses per-node channel names to avoid signal consumption/routing issues:
// Temporal signal channels consume messages on Receive — if multiple goroutines listen
// on the same channel, only one gets the signal and it's not re-delivered to others.
// Using per-node channels (e.g., "human-decision-{execID}") ensures each goroutine
// receives only its own signal.
const (
	SignalHumanDecisionPrefix = "human-decision-" // append execID for per-node channel
	SignalPause               = "pause"
	SignalResume              = "resume"
	SignalCancel              = "cancel"
)

// HumanDecisionSignal is the payload for the "human-decision-{execID}" signal
type HumanDecisionSignal struct {
	NodeExecutionID string `json:"nodeExecutionId"`
	Decision        string `json:"decision"` // "approved" or "rejected"
	Feedback        string `json:"feedback"`
	AttachmentRefs  string `json:"attachmentRefs,omitempty"` // NEW: gate attachment object storage refs
}

const (
	SignalRetryNode = "retry-node"
)

// RetryNodeSignal is the payload for the "retry-node" signal
type RetryNodeSignal struct {
	TemplateNodeID string `json:"templateNodeId"`
}
