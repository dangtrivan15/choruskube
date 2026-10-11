package credential

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/dangtrivan15/choruskube/worker/github"
)

// refreshMargin re-mints a cached App token this long before it expires, so a pod is never handed
// one that lapses mid-clone.
const refreshMargin = 5 * time.Minute

// AppTokenMinter exchanges a GitHub App's private key for an installation token.
// *github.Client satisfies it.
type AppTokenMinter interface {
	InstallationToken(ctx context.Context, appID, installationID string, privateKeyPEM []byte, now time.Time) (github.InstallationToken, error)
}

// local serves one tenant's own Fleet from files mounted into the Worker. Every value is read on
// each call, so a rotated secret takes effect without a restart. It ignores the run: one
// credential serves every run, which is wrong anywhere a Worker serves more than one tenant.
type local struct {
	dir string
	gh  AppTokenMinter
	now func() time.Time

	mu         sync.Mutex
	cached     github.InstallationToken
	cachedFor  [sha256.Size]byte
	haveCached bool
}

// NewLocal returns a Provider that reads credentials from dir, falling back to the environment
// for anything dir does not hold: GITHUB_PAT, GITHUB_APP_ID, GITHUB_APP_INSTALLATION_ID,
// GITHUB_APP_PRIVATE_KEY_PATH, CLAUDE_CODE_OAUTH_TOKEN, REGISTRY_HOST, REGISTRY_USERNAME and
// REGISTRY_PASSWORD. An empty dir means environment only.
func NewLocal(dir string, gh AppTokenMinter, now func() time.Time) Provider {
	if now == nil {
		now = time.Now
	}
	return &local{dir: dir, gh: gh, now: now}
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

func (l *local) GitHubToken(ctx context.Context, _ uuid.UUID) (Token, error) {
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
		return l.mint(ctx, *app)
	}
	if pat := strings.TrimSpace(os.Getenv("GITHUB_PAT")); pat != "" {
		return Token{Value: pat}, nil
	}
	app, err = appFromEnv()
	if err != nil {
		return Token{}, err
	}
	if app != nil {
		return l.mint(ctx, *app)
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

// mint holds the lock across the exchange so concurrent pods share one token rather than each
// minting their own against GitHub's rate limit.
func (l *local) mint(ctx context.Context, app appCredential) (Token, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	fp := app.fingerprint()
	if l.haveCached && l.cachedFor == fp && now.Before(l.cached.ExpiresAt.Add(-refreshMargin)) {
		return Token{Value: l.cached.Value, ExpiresAt: l.cached.ExpiresAt}, nil
	}
	minted, err := l.gh.InstallationToken(ctx, app.appID, app.installationID, app.pem, now)
	if err != nil {
		return Token{}, fmt.Errorf("mint GitHub App installation token: %w", err)
	}
	minted.Value = strings.TrimSpace(minted.Value)
	// Zero means the response named no lifetime; serving that from cache could outlive the token.
	l.haveCached = !minted.ExpiresAt.IsZero()
	l.cached, l.cachedFor = minted, fp
	return Token{Value: minted.Value, ExpiresAt: minted.ExpiresAt}, nil
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
