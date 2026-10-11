package callback

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"

	"github.com/google/uuid"

	"github.com/dangtrivan15/choruskube/worker/credential"
)

// RunBinding says which run an execution belongs to. RunOf answers from memory; ExecutionInRun
// asks the API server and fails unless the execution is in the run, for a Worker that restarted
// and lost what it knew.
type RunBinding interface {
	RunOf(execID uuid.UUID) (uuid.UUID, bool)
	ExecutionInRun(ctx context.Context, runID, execID uuid.UUID) error
}

// TokenSource supplies the GitHub token for a run. credential.Provider satisfies it.
type TokenSource interface {
	GitHubToken(ctx context.Context, runID uuid.UUID) (credential.Token, error)
}

var _ TokenSource = credential.Provider(nil)

// GitHubTokenHandler serves GET /api/v1/github-token?run_id=&execution_id=: an agent pod fetching
// the GitHub token for its run, authenticated by the same per-execution secret as its callbacks.
type GitHubTokenHandler struct {
	cache    *HashCache
	resolver SecretHashResolver
	runs     RunBinding
	tokens   TokenSource
}

// NewGitHubTokenHandler constructs a GitHubTokenHandler. cache and resolver are the ones the
// completion Handler authenticates with, so a pod proves itself here exactly as it does there.
func NewGitHubTokenHandler(cache *HashCache, resolver SecretHashResolver, runs RunBinding, tokens TokenSource) *GitHubTokenHandler {
	return &GitHubTokenHandler{cache: cache, resolver: resolver, runs: runs, tokens: tokens}
}

func (h *GitHubTokenHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	bearer := extractBearer(r)
	if bearer == "" {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	runID, err := uuid.Parse(r.URL.Query().Get("run_id"))
	if err != nil {
		http.Error(w, "invalid run_id", http.StatusBadRequest)
		return
	}
	execID, err := uuid.Parse(r.URL.Query().Get("execution_id"))
	if err != nil {
		http.Error(w, "invalid execution id", http.StatusBadRequest)
		return
	}

	ctx := r.Context()
	if !verifySecret(ctx, h.cache, h.resolver, runID, execID, bearer) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	// A valid secret proves the execution, not the run in the query: without this a pod could
	// name another run and be handed the credential resolved for that run's repository.
	if bound, known := h.runs.RunOf(execID); known {
		if bound != runID {
			http.Error(w, "execution does not belong to this run", http.StatusForbidden)
			return
		}
	} else if err := h.runs.ExecutionInRun(ctx, runID, execID); err != nil {
		slog.Warn("could not confirm execution belongs to run", "run_id", runID, "execution_id", execID, "error", err)
		http.Error(w, "execution does not belong to this run", http.StatusForbidden)
		return
	}

	tok, err := h.tokens.GitHubToken(ctx, runID)
	if err != nil || tok.Value == "" {
		slog.Warn("no usable GitHub credential for run", "run_id", runID, "execution_id", execID, "error", err)
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "no usable GitHub credential"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"token": tok.Value})
}

// writeJSON marks the response uncacheable: it can carry a bearer token a proxy must not keep.
func writeJSON(w http.ResponseWriter, status int, body map[string]string) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
