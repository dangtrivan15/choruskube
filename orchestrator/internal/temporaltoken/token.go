// Package temporaltoken holds a namespace-keyed credential for a control-plane Temporal client:
// a bearer token with its expiry, or a reason none is available. It generalizes the Worker's
// Fleet token cache for a client that is pushed its tokens rather than polling for them.
package temporaltoken

import (
	"fmt"
	"time"
)

// Token is a bearer token with its expiry. The value sits behind an unexported pointer: fmt
// cannot call String/GoString on a value held in another struct's unexported field, and instead
// prints that field's own fields by reflection — a pointer prints as an address, a string would
// print as itself. Keeping the value behind a pointer is what keeps that fallback token-free too.
type Token struct {
	value     *string
	ExpiresAt time.Time
}

// NewToken returns a Token holding value, expiring at expiresAt.
func NewToken(value string, expiresAt time.Time) Token {
	return Token{value: &value, ExpiresAt: expiresAt}
}

// String never includes the token value.
func (t Token) String() string {
	return fmt.Sprintf("Token{expires=%s}", t.ExpiresAt.UTC().Format(time.RFC3339))
}

// GoString never includes the token value.
func (t Token) GoString() string {
	return t.String()
}

// usableAt reports whether t can be presented at now. The SDK sends no header at all for an empty
// value, so a token without one would put a credential-less call on the wire.
func (t Token) usableAt(now time.Time) bool {
	return t.value != nil && *t.value != "" && t.ExpiresAt.After(now)
}

// stateKind distinguishes State's three outcomes.
type stateKind int

const (
	stateBearer stateKind = iota
	stateNone
	stateUnavailable
)

// State is the three-state outcome a supplier or roster entry pushes for one namespace: a
// bearer token, "no credential for this deployment", or "unavailable right now".
type State struct {
	kind  stateKind
	token Token
}

// Bearer is a namespace holding this token.
func Bearer(t Token) State { return State{kind: stateBearer, token: t} }

// None is "this deployment presents no credential for this namespace".
func None() State { return State{kind: stateNone} }

// Unavailable is "a credential should exist but none could be obtained right now".
func Unavailable() State { return State{kind: stateUnavailable} }

// String never includes the token value.
func (s State) String() string {
	switch s.kind {
	case stateBearer:
		return fmt.Sprintf("State{bearer, expires=%s}", s.token.ExpiresAt.UTC().Format(time.RFC3339))
	case stateNone:
		return "State{none}"
	case stateUnavailable:
		return "State{unavailable}"
	default:
		return "State{unknown}"
	}
}

// GoString never includes the token value.
func (s State) GoString() string {
	return s.String()
}
