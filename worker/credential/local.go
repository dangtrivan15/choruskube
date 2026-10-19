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

// refreshMargin re-mints a cached App token this long before it expires, so a pod is never handed
// one that lapses mid-clone.
const refreshMargin = 5 * time.Minute

// installationTTL caches an installation lookup for this long — see the shared contract in
// GitHubAppService#installation (API server): keyed by credential fingerprint, never by
// installation id alone.
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

	mu            sync.Mutex
	installations map[[sha256.Size]byte]cachedInstallation
	tokens        map[string]github.InstallationToken
}

type cachedInstallation struct {
	installation github.Installation
	expiresAt    time.Time
}

// NewLocal returns a Provider that reads credentials from dir, falling back to the environment
// for anything dir does not hold: GITHUB_PAT, GITHUB_APP_ID, GITHUB_APP_INSTALLATION_ID,
// GITHUB_APP_PRIVATE_KEY_PATH, CLAUDE_CODE_OAUTH_TOKEN, REGISTRY_HOST, REGISTRY_USERNAME and
// REGISTRY_PASSWORD. An empty dir means environment only. scopes supplies the run scope an App
// credential's mint is narrowed to; it is never consulted for a PAT.
func NewLocal(dir string, gh AppTokenMinter, scopes ScopeClient, now func() time.Time) Provider {
	if now == nil {
		now = time.Now
	}
	return &local{
		dir:           dir,
		gh:            gh,
		scopes:        scopes,
		now:           now,
		installations: make(map[[sha256.Size]byte]cachedInstallation),
		tokens:        make(map[string]github.InstallationToken),
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

func (l *local) AgentCredentials(context.Context, uuid.UUID) (Agent, bool, error) {
	claude, err := l.fileOrEnv("claude-oauth-token", "CLAUDE_CODE_OAUTH_TOKEN")
	if err != nil {
		return Agent{}, false, err
	}
	registry, err := l.registry()
	if err != nil {
		return Agent{}, false, err
	}
	if claude == "" && registry == nil {
		return Agent{}, false, nil
	}
	return Agent{ClaudeOAuthToken: claude, Registry: registry}, true, nil
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
