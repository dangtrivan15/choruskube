package credential

import "time"

// Kind is a credential a Worker may hold, named the same way across every status report: the
// Go sender, the API server's wire DTO, and the cloud's stored rows all agree on these strings.
type Kind string

const (
	KindGitHub   Kind = "github"
	KindClaude   Kind = "claude"
	KindRegistry Kind = "registry"
)

// allKinds is iterated in declaration order everywhere a full report is built, so a reader never
// has to guess which entry is missing from an incomplete list.
var allKinds = []Kind{KindGitHub, KindClaude, KindRegistry}

// Status is the outcome of checking one credential. The first four are the ones the cloud API's
// own credential checks already store; NotHeld is the one new value a Worker's self-check adds.
type Status string

const (
	StatusValid                   Status = "VALID"
	StatusExpired                 Status = "EXPIRED"
	StatusInsufficientPermissions Status = "INSUFFICIENT_PERMISSIONS"
	StatusUnreachable             Status = "UNREACHABLE"
	StatusNotHeld                 Status = "NOT_HELD"
)

// MaxHealthDetail caps HealthResult.Detail at this many runes. It must agree with the Java
// WorkerCredentialHealth record's cap: a detail truncated to a different length on each side
// would make the two disagree about what a Worker actually sent.
const MaxHealthDetail = 256

// HealthResult is one credential kind's latest check. Detail never carries any part of a secret:
// only a short, human-readable reason.
type HealthResult struct {
	Kind      Kind
	Status    Status
	Detail    string
	CheckedAt time.Time
}

// NotHeldReport is every kind reported NotHeld, checked at now — what a Worker holding nothing
// (control-plane) always reports, and the credential package's own answer when a check finds
// nothing configured for a given kind.
func NotHeldReport(now time.Time) []HealthResult {
	out := make([]HealthResult, len(allKinds))
	for i, k := range allKinds {
		out[i] = HealthResult{Kind: k, Status: StatusNotHeld, CheckedAt: now}
	}
	return out
}

// truncateDetail caps s to MaxHealthDetail runes. A detail is never a secret, but it must never
// exceed the wire contract's cap — truncating here keeps that promise even if the caller forgot.
func truncateDetail(s string) string {
	runes := []rune(s)
	if len(runes) <= MaxHealthDetail {
		return s
	}
	return string(runes[:MaxHealthDetail])
}
