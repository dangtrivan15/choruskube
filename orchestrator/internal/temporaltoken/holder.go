package temporaltoken

import (
	"context"
	"fmt"
	"log"
	"sort"
	"strings"
	"sync"
	"time"
)

// entry is what Holder keeps for one namespace. held is nil when the namespace has never been
// pushed a valid token, or was last pushed None; unavailable distinguishes "no credential by
// design" (held == nil, unavailable == false, including a namespace never pushed) from "a
// credential should exist but none is held" (held == nil, unavailable == true), which the two
// states resolve to differently in Credential.
type entry struct {
	held        *Token
	unavailable bool
}

// Holder is a namespace-keyed, push-fed holder of bearer tokens for a control-plane Temporal
// client. Update pushes a namespace's current credential state; Credential returns the per-call
// callback client.NewAPIKeyDynamicCredentials takes, which reads the current state on every
// call, so a token pushed later applies without redialling. A sync.RWMutex guards the map.
type Holder struct {
	mu      sync.RWMutex
	now     func() time.Time
	entries map[string]entry
}

// NewHolder returns a Holder whose notion of "now" is now, or time.Now if now is nil.
func NewHolder(now func() time.Time) *Holder {
	if now == nil {
		now = time.Now
	}
	return &Holder{now: now, entries: make(map[string]entry)}
}

// Update pushes namespace's current credential state.
//
//   - Bearer with a non-empty value and an expiry after now replaces the held token.
//   - Bearer already expired, or with an empty value, is treated as Unavailable.
//   - None clears any held token and marks the namespace credential-free.
//   - Unavailable keeps any held token.
func (h *Holder) Update(namespace string, s State) {
	h.mu.Lock()
	defer h.mu.Unlock()

	now := h.now()
	e := h.entries[namespace]

	if s.kind == stateBearer && s.token.usableAt(now) {
		wasValid := e.held != nil && e.held.ExpiresAt.After(now)
		tok := s.token
		e.held = &tok
		e.unavailable = false
		h.entries[namespace] = e
		if !wasValid {
			log.Printf("temporaltoken: namespace %q now holds a token, expires %s", namespace, formatExpiry(tok.ExpiresAt))
		}
		return
	}

	if s.kind == stateNone {
		delete(h.entries, namespace)
		return
	}

	// Unavailable, and a Bearer push that is not usable, land here: keep any held token.
	if e.held != nil {
		log.Printf("temporaltoken: namespace %q pushed unavailable, held token expires %s", namespace, formatExpiry(e.held.ExpiresAt))
	} else {
		log.Printf("temporaltoken: namespace %q pushed unavailable, none held", namespace)
		e.unavailable = true
		h.entries[namespace] = e
	}
}

// Holds reports whether namespace currently holds a usable, unexpired token. False for a
// namespace marked None, Unavailable with nothing held, or never pushed at all.
func (h *Holder) Holds(namespace string) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()

	e, ok := h.entries[namespace]
	return ok && e.held != nil && e.held.ExpiresAt.After(h.now())
}

// Credential returns the per-call credential callback for namespace. It reads the current state
// on every call:
//
//   - a held unexpired token returns that token;
//   - a namespace marked None, or one never pushed, returns "", nil — the SDK attaches no header;
//   - a held token that has expired, or an Unavailable push with nothing valid held, returns an
//     error naming the namespace only.
func (h *Holder) Credential(namespace string) func(context.Context) (string, error) {
	return func(ctx context.Context) (string, error) {
		h.mu.RLock()
		e, ok := h.entries[namespace]
		h.mu.RUnlock()

		if !ok {
			return "", nil
		}
		if e.held != nil {
			if e.held.ExpiresAt.After(h.now()) {
				return *e.held.value, nil
			}
			return "", fmt.Errorf("temporaltoken: no valid token held for namespace %q", namespace)
		}
		if e.unavailable {
			return "", fmt.Errorf("temporaltoken: no valid token held for namespace %q", namespace)
		}
		return "", nil
	}
}

// String lists namespaces and expiries only.
func (h *Holder) String() string {
	h.mu.RLock()
	defer h.mu.RUnlock()

	names := make([]string, 0, len(h.entries))
	for ns := range h.entries {
		names = append(names, ns)
	}
	sort.Strings(names)

	var b strings.Builder
	b.WriteString("Holder{")
	for i, ns := range names {
		if i > 0 {
			b.WriteString(", ")
		}
		e := h.entries[ns]
		switch {
		case e.held != nil:
			fmt.Fprintf(&b, "%s: expires=%s", ns, formatExpiry(e.held.ExpiresAt))
		case e.unavailable:
			fmt.Fprintf(&b, "%s: unavailable", ns)
		default:
			fmt.Fprintf(&b, "%s: none", ns)
		}
	}
	b.WriteString("}")
	return b.String()
}

func formatExpiry(t time.Time) string {
	return t.UTC().Format(time.RFC3339)
}
