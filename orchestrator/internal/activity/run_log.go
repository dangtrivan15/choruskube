package activity

import (
	"context"
	"fmt"
	"strings"

	"github.com/google/uuid"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/objectstore"
)

// --- Activity: InitRunLog ---

// InitRunLogParams contains the parameters for initializing a run log in object storage.
type InitRunLogParams struct {
	RunID   uuid.UUID
	OrgSlug string
}

// InitRunLog creates the initial run log file in object storage with a header.
// Called once at workflow start, before any node executes.
func (a *Activities) InitRunLog(ctx context.Context, params InitRunLogParams) error {
	baseKey := fmt.Sprintf("runs/%s/run_log.md", params.RunID)
	key := objectstore.PrefixPath(params.OrgSlug, baseKey)
	header := fmt.Sprintf("# Run Log — %s\n", params.RunID)
	return a.objectStoreClient.PutObject(ctx, key, []byte(header))
}

// --- Activity: AppendRunLog ---

// AppendRunLogParams contains the data for a single run log entry.
type AppendRunLogParams struct {
	RunID        uuid.UUID
	OrgSlug      string
	NodeLabel    string
	Status       string // "completed" or "failed"
	Iteration    int
	Result       string
	ErrorMessage string // empty if none
	ArtifactRefs string // JSON string, empty or "{}" if none
	RoutedTo     string // comma-separated target labels, empty for failed/terminal
}

// AppendRunLog reads the existing run log from object storage, appends a new entry,
// and writes it back. This is safe because the DAG executor processes
// completions sequentially — no concurrent appends occur.
// Temporal retries produce the same append (deterministic from params).
func (a *Activities) AppendRunLog(ctx context.Context, params AppendRunLogParams) error {
	baseKey := fmt.Sprintf("runs/%s/run_log.md", params.RunID)
	key := objectstore.PrefixPath(params.OrgSlug, baseKey)

	existing, err := a.objectStoreClient.GetObject(ctx, key)
	if err != nil {
		return fmt.Errorf("read run log: %w", err)
	}

	entry := buildRunLogEntry(params)
	updated := append(existing, []byte(entry)...)
	return a.objectStoreClient.PutObject(ctx, key, updated)
}

// buildRunLogEntry formats a single Markdown entry for the run log.
func buildRunLogEntry(p AppendRunLogParams) string {
	var sb strings.Builder

	// Heading: ## label (status[ → targets], iteration N)
	routing := ""
	if p.RoutedTo != "" {
		routing = " → " + p.RoutedTo
	}
	fmt.Fprintf(&sb, "\n## %s (%s%s, iteration %d)\n", p.NodeLabel, p.Status, routing, p.Iteration)

	// Result — always present
	fmt.Fprintf(&sb, "**Result:** %s\n", p.Result)

	// Error — omit if empty
	if p.ErrorMessage != "" {
		fmt.Fprintf(&sb, "**Error:** %s\n", p.ErrorMessage)
	}

	// Artifacts — omit if empty or bare "{}"
	if p.ArtifactRefs != "" && p.ArtifactRefs != "{}" {
		fmt.Fprintf(&sb, "**Artifacts:** %s\n", p.ArtifactRefs)
	}

	return sb.String()
}
