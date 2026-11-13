package credential

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/dangtrivan15/choruskube/worker/github"
)

// healthCheckTimeout bounds every outbound read CheckHealth makes, and the repository read a
// run's first PAT token triggers, so a stalled service can delay a registration or a token
// request by at most this long rather than hanging the caller indefinitely.
const healthCheckTimeout = 10 * time.Second

// maxVerifiedRuns bounds the "already verified this run's repositories" set. A Worker runs many
// runs over its lifetime and holds this in memory only, so an unbounded set would grow forever;
// the oldest entry is evicted first, same as the trade a restart already makes (every run is
// re-verified once more).
const maxVerifiedRuns = 1024

// GitHubChecker is the lightest authenticated GitHub reads a PAT health check and a run's
// repository-reachability check need. *github.Client satisfies it.
type GitHubChecker interface {
	User(ctx context.Context, token string) (github.UserCheck, error)
	Repository(ctx context.Context, token, ownerRepo string) error
}

// AnthropicChecker checks a Claude token the lightest way the API offers. *anthropic.Client
// satisfies it structurally -- this package never imports anthropic, so the dependency runs the
// other way: anthropic imports credential for the Status type it returns.
type AnthropicChecker interface {
	Check(ctx context.Context, token string) (Status, string)
}

// RegistryChecker checks a registry login the lightest way the API offers. registry.Check (the
// package function) satisfies this function type directly.
type RegistryChecker func(ctx context.Context, host, username, password string) (Status, string)

// refreshMargin re-mints a cached App token this long before it expires, so a pod is never handed
// one that lapses mid-clone.
const refreshMargin = 5 * time.Minute

// installationTTL caches an installation lookup for this long, keyed by credential fingerprint
// (never by installation id alone, which two different App credentials could share).
const installationTTL = 10 * time.Minute

// ScopeClient fetches a run's GitHub token scope from the control plane — the repositories and
// permissions the API server's scope resolver computed for it. *workload.Client satisfies it.
type ScopeClient interface {
	GitHubTokenScope(ctx context.Context, runID uuid.UUID) ([]string, map[string]string, error)
}

// AppTokenMinter exchanges a GitHub App's private key for an installation lookup or a narrowed
// token. *github.Client satisfies it.
type AppTokenMinter interface {
	Installation(ctx context.Context, appID, installationID string, privateKeyPEM []byte, now time.Time) (github.Installation, error)
	InstallationToken(ctx context.Context, appID, installationID string, privateKeyPEM []byte, now time.Time, req github.TokenRequest) (github.InstallationToken, error)
}

// local serves one tenant's own Fleet from files mounted into the Worker. Every value is read on
// each call, so a rotated secret takes effect without a restart. It ignores the run for
// credential selection: one credential serves every run, which is wrong anywhere a Worker serves
// more than one tenant. An App credential's mint is still narrowed per run, via scopes.
type local struct {
	dir    string
	gh     AppTokenMinter
	scopes ScopeClient
	now    func() time.Time

	ghChecker        GitHubChecker
	anthropicChecker AnthropicChecker
	registryChecker  RegistryChecker

	mu            sync.Mutex
	installations map[[sha256.Size]byte]cachedInstallation
	tokens        map[string]github.InstallationToken

	// verifiedMu guards the bounded set of runs whose repositories a PAT has already proven it
	// can reach, so GitHubToken checks reachability once per run rather than on every call.
	verifiedMu    sync.Mutex
	verifiedRuns  map[uuid.UUID]struct{}
	verifiedOrder []uuid.UUID
}

type cachedInstallation struct {
	installation github.Installation
	expiresAt    time.Time
}

// NewLocal returns a Provider that reads credentials from dir, falling back to the environment
// for anything dir does not hold: GITHUB_PAT, GITHUB_APP_ID, GITHUB_APP_INSTALLATION_ID,
// GITHUB_APP_PRIVATE_KEY_PATH, CLAUDE_CODE_OAUTH_TOKEN, REGISTRY_HOST, REGISTRY_USERNAME and
// REGISTRY_PASSWORD. An empty dir means environment only. scopes supplies the run scope an App
// credential's mint is narrowed to, and the repositories a PAT's first use for a run checks it can
// reach; it is never consulted for a PAT's minting (there is none). AgentCredentials never reads
// what prepare attached — a Claude-running node with no Claude token of its own is an error.
// ghChecker, anthropic and registryChecker are consulted only by CheckHealth and, for ghChecker,
// a PAT's first-token repository check.
func NewLocal(
	dir string, gh AppTokenMinter, scopes ScopeClient, now func() time.Time,
	ghChecker GitHubChecker, anthropic AnthropicChecker, registryChecker RegistryChecker,
) Provider {
	if now == nil {
		now = time.Now
	}
	return &local{
		dir:              dir,
		gh:               gh,
		scopes:           scopes,
		now:              now,
		ghChecker:        ghChecker,
		anthropicChecker: anthropic,
		registryChecker:  registryChecker,
		installations:    make(map[[sha256.Size]byte]cachedInstallation),
		tokens:           make(map[string]github.InstallationToken),
		verifiedRuns:     make(map[uuid.UUID]struct{}),
	}
}

type appCredential struct {
	appID, installationID string
	pem                   []byte
}

func (a appCredential) fingerprint() [sha256.Size]byte {
	h := sha256.New()
	for _, part := range [][]byte{[]byte(a.appID), []byte(a.installationID), a.pem} {
		h.Write(part)
		h.Write([]byte{0})
	}
	var sum [sha256.Size]byte
	copy(sum[:], h.Sum(nil))
	return sum
}

func (l *local) GitHubToken(ctx context.Context, runID uuid.UUID) (Token, error) {
	// The mounted folder is the deliberate setting and the environment only a fallback, so a
	// stray variable can never override a secret an operator mounted.
	pat, err := l.fileValue("github-pat")
	if err != nil {
		return Token{}, err
	}
	if pat != "" {
		if err := l.verifyRunRepositories(ctx, runID, pat); err != nil {
			return Token{}, err
		}
		return Token{Value: pat}, nil
	}
	app, err := l.appFromFiles()
	if err != nil {
		return Token{}, err
	}
	if app != nil {
		return l.mintScoped(ctx, runID, *app)
	}
	if pat := strings.TrimSpace(os.Getenv("GITHUB_PAT")); pat != "" {
		if err := l.verifyRunRepositories(ctx, runID, pat); err != nil {
			return Token{}, err
		}
		return Token{Value: pat}, nil
	}
	app, err = appFromEnv()
	if err != nil {
		return Token{}, err
	}
	if app != nil {
		return l.mintScoped(ctx, runID, *app)
	}
	return Token{}, fmt.Errorf("no GitHub credential configured: put github-pat or github-app/{app-id,installation-id,private-key.pem} "+
		"in CREDENTIALS_DIR (%s), or set GITHUB_PAT, or GITHUB_APP_ID with GITHUB_APP_INSTALLATION_ID and GITHUB_APP_PRIVATE_KEY_PATH", l.dirLabel())
}

// verifyRunRepositories checks, once per run, that a PAT can reach every repository the run's
// scope names -- a narrowed App mint already fails the same way for its own credential kind, so a
// PAT needs an explicit read to catch the same class of problem. A run already proven is skipped
// so a long-lived PAT is not re-read on every node of the same run.
func (l *local) verifyRunRepositories(ctx context.Context, runID uuid.UUID, pat string) error {
	if l.runVerified(runID) {
		return nil
	}
	if l.scopes == nil {
		return errors.New("no GitHub token scope source configured for a PAT credential")
	}
	repositories, _, err := l.scopes.GitHubTokenScope(ctx, runID)
	if err != nil {
		return fmt.Errorf("fetch GitHub token scope for run %s: %w", runID, err)
	}
	if l.ghChecker != nil {
		var unreachable []string
		for _, repo := range repositories {
			if err := l.checkRepositoryReachable(ctx, pat, repo); err != nil {
				unreachable = append(unreachable, fmt.Sprintf("%s (%s)", repo, reachabilityReason(err)))
			}
		}
		if len(unreachable) > 0 {
			sort.Strings(unreachable)
			return fmt.Errorf("this PAT cannot reach %s", strings.Join(unreachable, ", "))
		}
	}
	l.markRunVerified(runID)
	return nil
}

// reachabilityReason separates a repository GitHub refused from one this Worker could not ask
// about at all, so an operator does not read an outage as a missing grant.
func reachabilityReason(err error) string {
	var apiErr *github.APIError
	if errors.As(err, &apiErr) {
		return fmt.Sprintf("HTTP %d", apiErr.Status)
	}
	return "could not check: " + err.Error()
}

func (l *local) checkRepositoryReachable(ctx context.Context, pat, repo string) error {
	cctx, cancel := context.WithTimeout(ctx, healthCheckTimeout)
	defer cancel()
	return l.ghChecker.Repository(cctx, pat, repo)
}

func (l *local) runVerified(runID uuid.UUID) bool {
	l.verifiedMu.Lock()
	defer l.verifiedMu.Unlock()
	_, ok := l.verifiedRuns[runID]
	return ok
}

// markRunVerified records runID as proven reachable, evicting the oldest entry first once the
// bounded set is full.
func (l *local) markRunVerified(runID uuid.UUID) {
	l.verifiedMu.Lock()
	defer l.verifiedMu.Unlock()
	if _, ok := l.verifiedRuns[runID]; ok {
		return
	}
	if len(l.verifiedOrder) >= maxVerifiedRuns {
		oldest := l.verifiedOrder[0]
		l.verifiedOrder = l.verifiedOrder[1:]
		delete(l.verifiedRuns, oldest)
	}
	l.verifiedRuns[runID] = struct{}{}
	l.verifiedOrder = append(l.verifiedOrder, runID)
}

// AgentCredentials ignores attached: this Worker holds its own credentials and never launches on
// what the control plane attached. A registry login is optional (nil means an anonymous pull),
// but a Claude-running node with no Claude token of its own is an error.
func (l *local) AgentCredentials(_ context.Context, _ uuid.UUID, _ Agent, runsClaude bool) (Agent, error) {
	registry, err := l.registry()
	if err != nil {
		return Agent{}, err
	}
	if !runsClaude {
		return Agent{Registry: registry}, nil
	}
	claude, err := l.fileOrEnv("claude-oauth-token", "CLAUDE_CODE_OAUTH_TOKEN")
	if err != nil {
		return Agent{}, err
	}
	if claude == "" {
		return Agent{}, fmt.Errorf("%w: put claude-oauth-token in CREDENTIALS_DIR (%s) or set CLAUDE_CODE_OAUTH_TOKEN",
			ErrNoClaudeToken, l.dirLabel())
	}
	return Agent{ClaudeOAuthToken: claude, Registry: registry}, nil
}

func (l *local) registry() (*Registry, error) {
	fields := []struct{ file, env string }{
		{"registry/host", "REGISTRY_HOST"},
		{"registry/username", "REGISTRY_USERNAME"},
		{"registry/password", "REGISTRY_PASSWORD"},
	}
	values := make([]string, len(fields))
	var missing []string
	for i, f := range fields {
		v, err := l.fileOrEnv(f.file, f.env)
		if err != nil {
			return nil, err
		}
		values[i] = v
		if v == "" {
			missing = append(missing, fmt.Sprintf("%s (%s)", f.file, f.env))
		}
	}
	switch len(missing) {
	case 0:
		return &Registry{Host: values[0], Username: values[1], Password: values[2]}, nil
	case len(fields):
		return nil, nil
	default:
		return nil, fmt.Errorf("registry credentials are incomplete: missing %s", strings.Join(missing, ", "))
	}
}

// mintScoped fetches the run's scope and, only once it is non-empty, mints against it. The scope
// call happens on every App token request (never for a PAT, which never reaches here) because a
// restarted Worker has no other memory of a run's repositories.
func (l *local) mintScoped(ctx context.Context, runID uuid.UUID, app appCredential) (Token, error) {
	if l.scopes == nil {
		return Token{}, errors.New("no GitHub token scope source configured for an App credential")
	}
	repositories, permissions, err := l.scopes.GitHubTokenScope(ctx, runID)
	if err != nil {
		return Token{}, fmt.Errorf("fetch GitHub token scope for run %s: %w", runID, err)
	}
	if len(repositories) == 0 {
		// Refused before touching either cache or GitHub: GitHub reads a missing repository
		// list as "every repository", so a repo-less scope must never fall through to one.
		return Token{}, fmt.Errorf("run %s has no repository in its GitHub token scope", runID)
	}
	return l.mint(ctx, app, repositories, permissions)
}

// mint holds the lock across the exchange so concurrent pods share one installation lookup and
// one token per narrowed request rather than each minting its own against GitHub's rate limit.
func (l *local) mint(ctx context.Context, app appCredential, repositories []string, permissions map[string]string) (Token, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	fp := app.fingerprint()

	inst, err := l.installationLocked(ctx, app, fp, now)
	if err != nil {
		return Token{}, fmt.Errorf("look up GitHub App installation: %w", err)
	}

	req, err := narrow(inst, repositories, permissions)
	if err != nil {
		return Token{}, err
	}

	l.pruneExpiredTokensLocked(now)
	tokenKey := tokenCacheKey(fp, req)
	if cached, ok := l.tokens[tokenKey]; ok && !cached.ExpiresAt.IsZero() && now.Before(cached.ExpiresAt.Add(-refreshMargin)) {
		return Token{Value: cached.Value, ExpiresAt: cached.ExpiresAt}, nil
	}

	minted, err := l.gh.InstallationToken(ctx, app.appID, app.installationID, app.pem, now, req)
	if err != nil {
		var apiErr *github.APIError
		if errors.As(err, &apiErr) && apiErr.Status == http.StatusUnprocessableEntity {
			// A changed installation (repository selection or permissions) is re-read on the
			// next attempt rather than kept stale for up to installationTTL.
			delete(l.installations, fp)
			// GitHub's 422 names no repository, so this message is the only place an operator
			// learns which ones to add to the installation's selection.
			requested := make([]string, len(req.Repositories))
			for i, name := range req.Repositories {
				requested[i] = inst.Account + "/" + name
			}
			sort.Strings(requested)
			return Token{}, fmt.Errorf("GitHub rejected minting a token for %v under installation account %q; "+
				"each repository must be in the App installation's repository selection and every requested "+
				"permission granted: %w", requested, inst.Account, err)
		}
		return Token{}, fmt.Errorf("mint GitHub App installation token: %w", err)
	}
	minted.Value = strings.TrimSpace(minted.Value)
	if minted.ExpiresAt.IsZero() {
		// Zero means the response named no lifetime; serving that from cache could outlive the token.
		delete(l.tokens, tokenKey)
	} else {
		l.tokens[tokenKey] = minted
	}
	return Token{Value: minted.Value, ExpiresAt: minted.ExpiresAt}, nil
}

func (l *local) installationLocked(ctx context.Context, app appCredential, fp [sha256.Size]byte, now time.Time) (github.Installation, error) {
	if cached, ok := l.installations[fp]; ok && now.Before(cached.expiresAt) {
		return cached.installation, nil
	}
	inst, err := l.gh.Installation(ctx, app.appID, app.installationID, app.pem, now)
	if err != nil {
		return github.Installation{}, err
	}
	l.installations[fp] = cachedInstallation{installation: inst, expiresAt: now.Add(installationTTL)}
	return inst, nil
}

func (l *local) pruneExpiredTokensLocked(now time.Time) {
	for key, tok := range l.tokens {
		if !tok.ExpiresAt.IsZero() && !now.Before(tok.ExpiresAt) {
			delete(l.tokens, key)
		}
	}
}

// tokenCacheKey keys a minted token by credential fingerprint plus the exact narrowed request, so
// concurrent runs over different repository sets each keep their own token rather than evicting
// each other. json.Marshal sorts map keys, so two equal requests always produce the same key.
func tokenCacheKey(fp [sha256.Size]byte, req github.TokenRequest) string {
	canonical, _ := json.Marshal(req)
	return hex.EncodeToString(fp[:]) + "|" + string(canonical)
}

func (l *local) appFromFiles() (*appCredential, error) {
	names := []string{"github-app/app-id", "github-app/installation-id", "github-app/private-key.pem"}
	raw := make([][]byte, len(names))
	var missing []string
	found := 0
	for i, name := range names {
		b, err := l.readFile(name)
		if err != nil {
			return nil, err
		}
		if len(strings.TrimSpace(string(b))) == 0 {
			missing = append(missing, name)
			continue
		}
		raw[i] = b
		found++
	}
	switch found {
	case 0:
		return nil, nil
	case len(names):
		return &appCredential{
			appID:          strings.TrimSpace(string(raw[0])),
			installationID: strings.TrimSpace(string(raw[1])),
			pem:            raw[2],
		}, nil
	default:
		return nil, fmt.Errorf("GitHub App credentials in CREDENTIALS_DIR (%s) are incomplete: missing %s", l.dirLabel(), strings.Join(missing, ", "))
	}
}

func appFromEnv() (*appCredential, error) {
	appID := strings.TrimSpace(os.Getenv("GITHUB_APP_ID"))
	installationID := strings.TrimSpace(os.Getenv("GITHUB_APP_INSTALLATION_ID"))
	keyPath := strings.TrimSpace(os.Getenv("GITHUB_APP_PRIVATE_KEY_PATH"))
	var missing []string
	for _, f := range []struct{ name, value string }{
		{"GITHUB_APP_ID", appID}, {"GITHUB_APP_INSTALLATION_ID", installationID}, {"GITHUB_APP_PRIVATE_KEY_PATH", keyPath},
	} {
		if f.value == "" {
			missing = append(missing, f.name)
		}
	}
	switch len(missing) {
	case 3:
		return nil, nil
	case 0:
	default:
		return nil, fmt.Errorf("GitHub App environment is incomplete: missing %s", strings.Join(missing, ", "))
	}
	pem, err := os.ReadFile(keyPath)
	if err != nil {
		return nil, fmt.Errorf("read GITHUB_APP_PRIVATE_KEY_PATH: %w", err)
	}
	if len(strings.TrimSpace(string(pem))) == 0 {
		return nil, errors.New("GITHUB_APP_PRIVATE_KEY_PATH names an empty file")
	}
	return &appCredential{appID: appID, installationID: installationID, pem: pem}, nil
}

// fileOrEnv is the value of a file under the credentials folder, or of env when the file is
// absent or blank. A file that exists but cannot be read is an error, never a silent fallback.
func (l *local) fileOrEnv(rel, env string) (string, error) {
	v, err := l.fileValue(rel)
	if err != nil || v != "" {
		return v, err
	}
	return strings.TrimSpace(os.Getenv(env)), nil
}

// fileValue is the trimmed content of a file under the credentials folder; "" when the folder is
// unset, the file is absent, or it holds only whitespace.
func (l *local) fileValue(rel string) (string, error) {
	b, err := l.readFile(rel)
	return strings.TrimSpace(string(b)), err
}

// readFile returns nil, nil for a file that does not exist. Its errors carry the path the OS
// reports and never the content.
func (l *local) readFile(rel string) ([]byte, error) {
	if l.dir == "" {
		return nil, nil
	}
	b, err := os.ReadFile(filepath.Join(l.dir, filepath.FromSlash(rel)))
	if errors.Is(err, fs.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("read credential file: %w", err)
	}
	return b, nil
}

func (l *local) dirLabel() string {
	if l.dir == "" {
		return "unset"
	}
	return l.dir
}

// CheckHealth re-reads the credentials folder and env on every call, the same as GitHubToken, so
// a credential rotated in place is picked up by the next check rather than cached from startup.
// The kinds are checked concurrently, so the startup check that precedes the first registration
// waits at most one healthCheckTimeout rather than one per kind.
func (l *local) CheckHealth(ctx context.Context) []HealthResult {
	checks := []func(context.Context) HealthResult{l.checkGitHub, l.checkClaude, l.checkRegistryHealth}
	out := make([]HealthResult, len(checks))
	var wg sync.WaitGroup
	for i, check := range checks {
		wg.Add(1)
		go func() {
			defer wg.Done()
			out[i] = check(ctx)
		}()
	}
	wg.Wait()
	return out
}

func (l *local) checkGitHub(ctx context.Context) HealthResult {
	now := l.now()
	pat, err := l.fileValue("github-pat")
	if err != nil {
		return malformedGitHub(err, now)
	}
	if pat != "" {
		return l.checkPAT(ctx, pat, now)
	}
	app, err := l.appFromFiles()
	if err != nil {
		return malformedGitHub(err, now)
	}
	if app != nil {
		return l.checkApp(ctx, *app, now)
	}
	if envPAT := strings.TrimSpace(os.Getenv("GITHUB_PAT")); envPAT != "" {
		return l.checkPAT(ctx, envPAT, now)
	}
	app, err = appFromEnv()
	if err != nil {
		return malformedGitHub(err, now)
	}
	if app != nil {
		return l.checkApp(ctx, *app, now)
	}
	return HealthResult{Kind: KindGitHub, Status: StatusNotHeld, CheckedAt: now}
}

// malformedGitHub reports a partial or unreadable credential set as Expired, naming the missing
// file or variable -- the same message GitHubToken would fail with, which never names a secret,
// only a path or an env var name.
func malformedGitHub(err error, now time.Time) HealthResult {
	return HealthResult{Kind: KindGitHub, Status: StatusExpired, Detail: truncateDetail(err.Error()), CheckedAt: now}
}

// repoScopes splits an X-OAuth-Scopes header the way GitHub sends it: comma-separated, each
// optionally followed by whitespace.
func repoScopes(header string) []string {
	parts := strings.Split(header, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		out = append(out, strings.TrimSpace(p))
	}
	return out
}

func hasScope(header, want string) bool {
	for _, s := range repoScopes(header) {
		if s == want {
			return true
		}
	}
	return false
}

// checkPAT classifies a PAT the same way an API server's own PAT check does: 401 expired, 403
// insufficient permissions, any other non-2xx unreachable. On success a missing scopes header is a
// fine-grained PAT (valid), and a present header without "repo" is insufficient permissions.
func (l *local) checkPAT(ctx context.Context, pat string, now time.Time) HealthResult {
	if l.ghChecker == nil {
		return HealthResult{Kind: KindGitHub, Status: StatusUnreachable, Detail: "no GitHub checker configured", CheckedAt: now}
	}
	cctx, cancel := context.WithTimeout(ctx, healthCheckTimeout)
	defer cancel()
	check, err := l.ghChecker.User(cctx, pat)
	if err != nil {
		return HealthResult{Kind: KindGitHub, Status: StatusUnreachable, Detail: "GitHub request failed", CheckedAt: now}
	}
	switch check.Status {
	case http.StatusUnauthorized:
		return HealthResult{Kind: KindGitHub, Status: StatusExpired, Detail: fmt.Sprintf("GitHub API returned HTTP %d", check.Status), CheckedAt: now}
	case http.StatusForbidden:
		return HealthResult{Kind: KindGitHub, Status: StatusInsufficientPermissions, Detail: fmt.Sprintf("GitHub API returned HTTP %d", check.Status), CheckedAt: now}
	}
	if check.Status >= 400 {
		return HealthResult{Kind: KindGitHub, Status: StatusUnreachable, Detail: fmt.Sprintf("GitHub API returned HTTP %d", check.Status), CheckedAt: now}
	}
	if !check.ScopesPresent {
		return HealthResult{Kind: KindGitHub, Status: StatusValid, Detail: "token is valid (fine-grained PAT, scopes not verifiable via header)", CheckedAt: now}
	}
	if !hasScope(check.Scopes, "repo") {
		return HealthResult{
			Kind: KindGitHub, Status: StatusInsufficientPermissions,
			Detail: truncateDetail("token is missing the required 'repo' scope; current scopes: " + check.Scopes), CheckedAt: now,
		}
	}
	return HealthResult{Kind: KindGitHub, Status: StatusValid, CheckedAt: now}
}

// checkApp looks up the installation and mints nothing: 200 and not suspended is valid, a
// suspended installation or a 401/404 is expired, 403 is insufficient permissions, and a network
// error, timeout or any other status (including 5xx) is unreachable.
func (l *local) checkApp(ctx context.Context, app appCredential, now time.Time) HealthResult {
	cctx, cancel := context.WithTimeout(ctx, healthCheckTimeout)
	defer cancel()
	inst, err := l.gh.Installation(cctx, app.appID, app.installationID, app.pem, l.now())
	if err != nil {
		var apiErr *github.APIError
		if errors.As(err, &apiErr) {
			switch apiErr.Status {
			case http.StatusUnauthorized, http.StatusNotFound:
				return HealthResult{Kind: KindGitHub, Status: StatusExpired, Detail: truncateDetail(apiErr.Error()), CheckedAt: now}
			case http.StatusForbidden:
				return HealthResult{Kind: KindGitHub, Status: StatusInsufficientPermissions, Detail: truncateDetail(apiErr.Error()), CheckedAt: now}
			default:
				return HealthResult{Kind: KindGitHub, Status: StatusUnreachable, Detail: truncateDetail(apiErr.Error()), CheckedAt: now}
			}
		}
		return HealthResult{Kind: KindGitHub, Status: StatusUnreachable, Detail: "GitHub App installation lookup failed", CheckedAt: now}
	}
	if inst.Suspended {
		return HealthResult{Kind: KindGitHub, Status: StatusExpired, Detail: "GitHub App installation is suspended", CheckedAt: now}
	}
	return HealthResult{Kind: KindGitHub, Status: StatusValid, CheckedAt: now}
}

// checkClaude reads the Claude token the same way AgentCredentials does; an absent token is
// NotHeld rather than a failing check, since a Worker that never runs Claude nodes legitimately
// holds none.
func (l *local) checkClaude(ctx context.Context) HealthResult {
	now := l.now()
	claude, err := l.fileOrEnv("claude-oauth-token", "CLAUDE_CODE_OAUTH_TOKEN")
	if err != nil {
		return HealthResult{Kind: KindClaude, Status: StatusExpired, Detail: truncateDetail(err.Error()), CheckedAt: now}
	}
	if claude == "" {
		return HealthResult{Kind: KindClaude, Status: StatusNotHeld, CheckedAt: now}
	}
	if l.anthropicChecker == nil {
		return HealthResult{Kind: KindClaude, Status: StatusUnreachable, Detail: "no Anthropic checker configured", CheckedAt: now}
	}
	cctx, cancel := context.WithTimeout(ctx, healthCheckTimeout)
	defer cancel()
	status, detail := l.anthropicChecker.Check(cctx, claude)
	return HealthResult{Kind: KindClaude, Status: status, Detail: truncateDetail(detail), CheckedAt: now}
}

// checkRegistryHealth reads the registry login the same way AgentCredentials does, via the
// registry() helper; an anonymous pull (no credential at all) is NotHeld, while an incomplete set
// is Expired naming what is missing -- registry() already builds that message.
func (l *local) checkRegistryHealth(ctx context.Context) HealthResult {
	now := l.now()
	reg, err := l.registry()
	if err != nil {
		return HealthResult{Kind: KindRegistry, Status: StatusExpired, Detail: truncateDetail(err.Error()), CheckedAt: now}
	}
	if reg == nil {
		return HealthResult{Kind: KindRegistry, Status: StatusNotHeld, CheckedAt: now}
	}
	if l.registryChecker == nil {
		return HealthResult{Kind: KindRegistry, Status: StatusUnreachable, Detail: "no registry checker configured", CheckedAt: now}
	}
	cctx, cancel := context.WithTimeout(ctx, healthCheckTimeout)
	defer cancel()
	status, detail := l.registryChecker(cctx, reg.Host, reg.Username, reg.Password)
	return HealthResult{Kind: KindRegistry, Status: status, Detail: truncateDetail(detail), CheckedAt: now}
}
