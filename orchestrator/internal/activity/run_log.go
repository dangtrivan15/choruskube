package activity

import (
	"context"
	"fmt"
	"strings"
	"unicode/utf8"

	"github.com/google/uuid"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/objectstore"
)

// maxRunLogFieldBytes bounds Result, ErrorMessage and ArtifactRefs so a Worker outside our
// network, or a runaway agent result, cannot grow a run log entry past a fixed size: every later
// agent reads the whole log as its input. 20000 characters of capped reviewer feedback are at
// most 60000 bytes even in 3-byte UTF-8, leaving headroom for the text the server adds around it.
const maxRunLogFieldBytes = 64 << 10

// capRunLogField cuts s to maxRunLogFieldBytes, never inside a UTF-8 rune, and appends a marker
// reporting what was kept. A value at or under the cap is returned unchanged.
func capRunLogField(s string) string {
	if len(s) <= maxRunLogFieldBytes {
		return s
	}
	cut := maxRunLogFieldBytes
	for cut > 0 && !utf8.RuneStart(s[cut]) {
		cut--
	}
	return fmt.Sprintf("%s\n… [truncated by the run log: %d of %d bytes kept]", s[:cut], cut, len(s))
}

// --- Activity: InitRunLog ---

// InitRunLogParams contains the parameters for initializing a run log in object storage.
type InitRunLogParams struct {
	RunID   uuid.UUID
	OrgSlug string
}

// InitRunLog creates the initial run log file in object storage with a header.
// Called once at workflow start, before any node executes.
func (a *Activities) InitRunLog(ctx context.Context, params InitRunLogParams) error {
	if err := guardRun(ctx, params.RunID); err != nil {
		return err
	}
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
// Result, ErrorMessage and ArtifactRefs are each capped to maxRunLogFieldBytes before
// formatting, so a Worker or agent cannot grow one entry past a fixed size.
func (a *Activities) AppendRunLog(ctx context.Context, params AppendRunLogParams) error {
	if err := guardRun(ctx, params.RunID); err != nil {
		return err
	}
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
	fmt.Fprintf(&sb, "**Result:** %s\n", capRunLogField(p.Result))

	// Error — omit if empty. Emptiness is checked on the raw value: the cap never turns a
	// non-empty field into one that looks absent.
	if p.ErrorMessage != "" {
		fmt.Fprintf(&sb, "**Error:** %s\n", capRunLogField(p.ErrorMessage))
	}

	// Artifacts — omit if empty or bare "{}", checked on the raw value for the same reason.
	if p.ArtifactRefs != "" && p.ArtifactRefs != "{}" {
		fmt.Fprintf(&sb, "**Artifacts:** %s\n", capRunLogField(p.ArtifactRefs))
	}

	return sb.String()
}
