package temporaltoken

import (
	"bytes"
	"context"
	"fmt"
	"log"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// clock is an injected, mutable "now" for deterministic tests.
type clock struct {
	mu  sync.Mutex
	now time.Time
}

func newClock(start time.Time) *clock {
	return &clock{now: start}
}

func (c *clock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *clock) advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = c.now.Add(d)
}

func captureLog(t *testing.T, fn func()) string {
	t.Helper()
	var buf bytes.Buffer
	prevOut := log.Writer()
	prevFlags := log.Flags()
	log.SetOutput(&buf)
	log.SetFlags(0)
	defer func() {
		log.SetOutput(prevOut)
		log.SetFlags(prevFlags)
	}()
	fn()
	return buf.String()
}

func TestCredential_NeverPushedNamespace_YieldsNoCredential(t *testing.T) {
	h := NewHolder(nil)
	val, err := h.Credential("ns")(context.Background())
	require.NoError(t, err)
	assert.Empty(t, val)
}

func TestCredential_Bearer_YieldsPushedToken(t *testing.T) {
	c := newClock(time.Unix(1000, 0))
	h := NewHolder(c.Now)
	h.Update("ns", Bearer(NewToken("tok-a", time.Unix(2000, 0))))

	cred := h.Credential("ns")
	val, err := cred(context.Background())
	require.NoError(t, err)
	assert.Equal(t, "tok-a", val)

	// A second push is served by the next call on the same callback, without re-obtaining it.
	h.Update("ns", Bearer(NewToken("tok-b", time.Unix(3000, 0))))
	val, err = cred(context.Background())
	require.NoError(t, err)
	assert.Equal(t, "tok-b", val)
}

func TestCredential_TwoNamespaces_YieldTheirOwnTokens(t *testing.T) {
	c := newClock(time.Unix(1000, 0))
	h := NewHolder(c.Now)
	h.Update("ns-a", Bearer(NewToken("tok-a", time.Unix(2000, 0))))
	h.Update("ns-b", Bearer(NewToken("tok-b", time.Unix(2000, 0))))

	valA, err := h.Credential("ns-a")(context.Background())
	require.NoError(t, err)
	assert.Equal(t, "tok-a", valA)
	valB, err := h.Credential("ns-b")(context.Background())
	require.NoError(t, err)
	assert.Equal(t, "tok-b", valB)
}

func TestCredential_UnavailableAfterBearer_KeepsServingUntilExpiry(t *testing.T) {
	c := newClock(time.Unix(1000, 0))
	h := NewHolder(c.Now)
	h.Update("ns-prod", Bearer(NewToken("tok-a", time.Unix(1100, 0))))
	h.Update("ns-prod", Unavailable())

	val, err := h.Credential("ns-prod")(context.Background())
	require.NoError(t, err, "the held token is still valid")
	assert.Equal(t, "tok-a", val)

	c.advance(200 * time.Second) // now 1200, past the 1100 expiry
	_, err = h.Credential("ns-prod")(context.Background())
	require.Error(t, err, "the held token has expired")
	assert.Contains(t, err.Error(), "ns-prod")
	assert.NotContains(t, err.Error(), "tok-a")
}

func TestCredential_UnavailableWithNothingHeld_Errors(t *testing.T) {
	c := newClock(time.Unix(1000, 0))
	h := NewHolder(c.Now)
	h.Update("ns-prod", Unavailable())

	_, err := h.Credential("ns-prod")(context.Background())
	require.Error(t, err)
	assert.Contains(t, err.Error(), "ns-prod")
}

func TestCredential_ExpiredBearerPush_IsTreatedAsUnavailable(t *testing.T) {
	c := newClock(time.Unix(1000, 0))
	h := NewHolder(c.Now)
	h.Update("ns", Bearer(NewToken("tok-a", time.Unix(500, 0)))) // already expired

	_, err := h.Credential("ns")(context.Background())
	require.Error(t, err, "an already-expired Bearer push holds nothing valid")
}

func TestCredential_BearerWithoutValue_IsTreatedAsUnavailable(t *testing.T) {
	c := newClock(time.Unix(1000, 0))
	future := time.Unix(2000, 0)
	for name, tok := range map[string]Token{
		"empty value": NewToken("", future),
		"zero Token":  {ExpiresAt: future},
	} {
		t.Run(name, func(t *testing.T) {
			h := NewHolder(c.Now)
			h.Update("ns", Bearer(tok))

			// "", nil would make the SDK send the call with no header at all.
			_, err := h.Credential("ns")(context.Background())
			require.Error(t, err, "a Bearer push with no value holds nothing valid")
		})
	}
}

func TestCredential_BearerWithoutValue_KeepsHeldToken(t *testing.T) {
	c := newClock(time.Unix(1000, 0))
	h := NewHolder(c.Now)
	h.Update("ns", Bearer(NewToken("tok-a", time.Unix(2000, 0))))
	h.Update("ns", Bearer(NewToken("", time.Unix(3000, 0))))

	val, err := h.Credential("ns")(context.Background())
	require.NoError(t, err)
	assert.Equal(t, "tok-a", val, "an empty push must not replace a valid token")
}

func TestCredential_NoneAfterBearer_YieldsNoCredential(t *testing.T) {
	c := newClock(time.Unix(1000, 0))
	h := NewHolder(c.Now)
	h.Update("ns", Bearer(NewToken("tok-a", time.Unix(2000, 0))))
	h.Update("ns", None())

	val, err := h.Credential("ns")(context.Background())
	require.NoError(t, err)
	assert.Empty(t, val)
}

func TestLogging_OneLineForFirstTokenNoneForRenewalOnePerUnavailableAndRegain(t *testing.T) {
	c := newClock(time.Unix(1000, 0))
	h := NewHolder(c.Now)

	out := captureLog(t, func() {
		h.Update("ns", Bearer(NewToken("tok-a", time.Unix(1100, 0)))) // first token: 1 line
		h.Update("ns", Bearer(NewToken("tok-b", time.Unix(1200, 0)))) // renewal while still valid: 0 lines
		h.Update("ns", Unavailable())                                 // held, still valid (1200>1000): 1 line
		c.advance(300 * time.Second)                                  // now 1300, held token (1200) now expired
		h.Update("ns", Unavailable())                                 // nothing valid held any more: 1 line
		h.Update("ns", Bearer(NewToken("tok-c", time.Unix(2000, 0)))) // regains a token: 1 line
	})

	lines := nonEmptyLines(out)
	require.Len(t, lines, 4, out)
	// The rollout check greps for a namespace holding a token, so the line carries both.
	assert.Contains(t, lines[0], `"ns"`)
	assert.Contains(t, lines[0], formatExpiry(time.Unix(1100, 0)))
	for _, l := range lines {
		for _, tok := range []string{"tok-a", "tok-b", "tok-c"} {
			assert.NotContains(t, l, tok, "log line leaked a token")
		}
	}
}

func nonEmptyLines(s string) []string {
	var out []string
	for _, l := range strings.Split(s, "\n") {
		if strings.TrimSpace(l) != "" {
			out = append(out, l)
		}
	}
	return out
}

func TestHolderString_IsTokenFree(t *testing.T) {
	c := newClock(time.Unix(1000, 0))
	h := NewHolder(c.Now)
	h.Update("ns-a", Bearer(NewToken("tok-a", time.Unix(2000, 0))))
	h.Update("ns-b", Unavailable())

	s := h.String()
	assert.NotContains(t, s, "tok-a")
	assert.Contains(t, s, "ns-a")
	assert.Contains(t, s, "ns-b")
}

func TestConcurrentUpdateAndCredential(t *testing.T) {
	c := newClock(time.Unix(1000, 0))
	h := NewHolder(c.Now)
	const namespaces = 8
	const iterations = 200

	var wg sync.WaitGroup
	for i := 0; i < namespaces; i++ {
		ns := fmt.Sprintf("ns-%d", i)
		wg.Add(2)
		go func() {
			defer wg.Done()
			for j := 0; j < iterations; j++ {
				h.Update(ns, Bearer(NewToken("tok", time.Unix(9999, 0))))
			}
		}()
		go func() {
			defer wg.Done()
			cred := h.Credential(ns)
			for j := 0; j < iterations; j++ {
				_, _ = cred(context.Background())
				_ = h.String()
			}
		}()
	}
	wg.Wait()
}
