package temporaltoken

import (
	"bytes"
	"context"
	"log"
	"strings"
	"sync"
	"testing"
	"time"
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
	if err != nil {
		t.Fatalf("err = %v, want nil", err)
	}
	if val != "" {
		t.Fatalf("value = %q, want empty", val)
	}
}

func TestCredential_Bearer_YieldsPushedToken(t *testing.T) {
	c := newClock(time.Unix(1000, 0))
	h := NewHolder(c.Now)
	h.Update("ns", Bearer(NewToken("tok-a", time.Unix(2000, 0))))

	cred := h.Credential("ns")
	val, err := cred(context.Background())
	if err != nil {
		t.Fatalf("err = %v, want nil", err)
	}
	if val != "tok-a" {
		t.Fatalf("value = %q, want tok-a", val)
	}

	// A second push is served by the next call on the same callback, without re-obtaining it.
	h.Update("ns", Bearer(NewToken("tok-b", time.Unix(3000, 0))))
	val, err = cred(context.Background())
	if err != nil {
		t.Fatalf("err = %v, want nil", err)
	}
	if val != "tok-b" {
		t.Fatalf("value = %q, want tok-b", val)
	}
}

func TestCredential_TwoNamespaces_YieldTheirOwnTokens(t *testing.T) {
	c := newClock(time.Unix(1000, 0))
	h := NewHolder(c.Now)
	h.Update("ns-a", Bearer(NewToken("tok-a", time.Unix(2000, 0))))
	h.Update("ns-b", Bearer(NewToken("tok-b", time.Unix(2000, 0))))

	valA, err := h.Credential("ns-a")(context.Background())
	if err != nil || valA != "tok-a" {
		t.Fatalf("ns-a: value=%q err=%v, want tok-a/nil", valA, err)
	}
	valB, err := h.Credential("ns-b")(context.Background())
	if err != nil || valB != "tok-b" {
		t.Fatalf("ns-b: value=%q err=%v, want tok-b/nil", valB, err)
	}
}

func TestCredential_UnavailableAfterBearer_KeepsServingUntilExpiry(t *testing.T) {
	c := newClock(time.Unix(1000, 0))
	h := NewHolder(c.Now)
	h.Update("ns", Bearer(NewToken("tok-a", time.Unix(1100, 0))))
	h.Update("ns", Unavailable())

	val, err := h.Credential("ns")(context.Background())
	if err != nil || val != "tok-a" {
		t.Fatalf("value=%q err=%v, want tok-a/nil while still valid", val, err)
	}

	c.advance(200 * time.Second) // now 1200, past the 1100 expiry
	_, err = h.Credential("ns")(context.Background())
	if err == nil {
		t.Fatalf("err = nil, want an error once the held token has expired")
	}
	if !strings.Contains(err.Error(), "ns") {
		t.Fatalf("error %q does not name the namespace", err.Error())
	}
	if strings.Contains(err.Error(), "tok-a") {
		t.Fatalf("error %q leaks the token", err.Error())
	}
}

func TestCredential_UnavailableWithNothingHeld_Errors(t *testing.T) {
	c := newClock(time.Unix(1000, 0))
	h := NewHolder(c.Now)
	h.Update("ns", Unavailable())

	_, err := h.Credential("ns")(context.Background())
	if err == nil {
		t.Fatalf("err = nil, want an error for unavailable with nothing held")
	}
	if !strings.Contains(err.Error(), "ns") {
		t.Fatalf("error %q does not name the namespace", err.Error())
	}
}

func TestCredential_ExpiredBearerPush_IsTreatedAsUnavailable(t *testing.T) {
	c := newClock(time.Unix(1000, 0))
	h := NewHolder(c.Now)
	h.Update("ns", Bearer(NewToken("tok-a", time.Unix(500, 0)))) // already expired

	_, err := h.Credential("ns")(context.Background())
	if err == nil {
		t.Fatalf("err = nil, want an error: an already-expired Bearer push holds nothing valid")
	}
}

func TestCredential_NoneAfterBearer_YieldsNoCredential(t *testing.T) {
	c := newClock(time.Unix(1000, 0))
	h := NewHolder(c.Now)
	h.Update("ns", Bearer(NewToken("tok-a", time.Unix(2000, 0))))
	h.Update("ns", None())

	val, err := h.Credential("ns")(context.Background())
	if err != nil {
		t.Fatalf("err = %v, want nil after None", err)
	}
	if val != "" {
		t.Fatalf("value = %q, want empty after None", val)
	}
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
	if len(lines) != 4 {
		t.Fatalf("got %d log lines, want 4:\n%s", len(lines), out)
	}
	for _, l := range lines {
		if strings.Contains(l, "tok-a") || strings.Contains(l, "tok-b") || strings.Contains(l, "tok-c") {
			t.Fatalf("log line leaked a token: %q", l)
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
	if strings.Contains(s, "tok-a") {
		t.Fatalf("Holder.String() leaked a token: %q", s)
	}
	if !strings.Contains(s, "ns-a") || !strings.Contains(s, "ns-b") {
		t.Fatalf("Holder.String() = %q, want both namespaces listed", s)
	}
}

func TestConcurrentUpdateAndCredential(t *testing.T) {
	c := newClock(time.Unix(1000, 0))
	h := NewHolder(c.Now)
	const namespaces = 8
	const iterations = 200

	var wg sync.WaitGroup
	for i := 0; i < namespaces; i++ {
		ns := nsName(i)
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

func nsName(i int) string {
	return "ns-" + string(rune('a'+i))
}
