package temporaltoken

import (
	"fmt"
	"strings"
	"testing"
	"time"
)

const secretValue = "super-secret-bearer-value-12345"

var fixedExpiry = time.Date(2026, 11, 20, 12, 0, 0, 0, time.UTC)

type exportedHolder struct {
	Tok Token
	St  State
}

type unexportedHolder struct {
	tok Token
	st  State
}

func formsOf(v any) []string {
	return []string{
		fmt.Sprintf("%v", v),
		fmt.Sprintf("%+v", v),
		fmt.Sprintf("%#v", v),
		fmt.Sprintf("%s", v),
	}
}

func assertTokenFree(t *testing.T, forms []string) {
	t.Helper()
	for _, f := range forms {
		if strings.Contains(f, secretValue) {
			t.Fatalf("formatted output leaked the token value: %q", f)
		}
	}
}

func assertContainsExpiry(t *testing.T, forms []string) {
	t.Helper()
	want := fixedExpiry.Format("2006-01-02")
	for _, f := range forms {
		if !strings.Contains(f, want) {
			t.Fatalf("formatted output %q does not contain the expiry date %q", f, want)
		}
	}
}

func TestToken_FormsAreTokenFreeAndCarryExpiry(t *testing.T) {
	tok := NewToken(secretValue, fixedExpiry)
	forms := formsOf(tok)
	assertTokenFree(t, forms)
	assertContainsExpiry(t, forms)
}

func TestState_BearerFormsAreTokenFreeAndCarryExpiry(t *testing.T) {
	s := Bearer(NewToken(secretValue, fixedExpiry))
	forms := formsOf(s)
	assertTokenFree(t, forms)
	assertContainsExpiry(t, forms)
}

func TestState_NoneAndUnavailableAreTokenFree(t *testing.T) {
	for _, s := range []State{None(), Unavailable()} {
		assertTokenFree(t, formsOf(s))
	}
}

func TestExportedFieldHolder_IsTokenFree(t *testing.T) {
	h := exportedHolder{
		Tok: NewToken(secretValue, fixedExpiry),
		St:  Bearer(NewToken(secretValue, fixedExpiry)),
	}
	forms := formsOf(h)
	assertTokenFree(t, forms)
	assertContainsExpiry(t, forms)
}

func TestUnexportedFieldHolder_IsTokenFree(t *testing.T) {
	h := unexportedHolder{
		tok: NewToken(secretValue, fixedExpiry),
		st:  Bearer(NewToken(secretValue, fixedExpiry)),
	}
	forms := formsOf(h)
	assertTokenFree(t, forms)
}
