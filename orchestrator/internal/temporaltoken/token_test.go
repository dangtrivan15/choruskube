package temporaltoken

import (
	"fmt"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
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
		assert.NotContains(t, f, secretValue, "formatted output leaked the token value")
	}
}

func assertContainsExpiry(t *testing.T, forms []string) {
	t.Helper()
	for _, f := range forms {
		assert.Contains(t, f, fixedExpiry.Format("2006-01-02"), "formatted output does not carry the expiry")
	}
}

func TestToken_FormsAreTokenFreeAndCarryExpiry(t *testing.T) {
	forms := formsOf(NewToken(secretValue, fixedExpiry))
	assertTokenFree(t, forms)
	assertContainsExpiry(t, forms)
}

func TestState_BearerFormsAreTokenFreeAndCarryExpiry(t *testing.T) {
	forms := formsOf(Bearer(NewToken(secretValue, fixedExpiry)))
	assertTokenFree(t, forms)
	assertContainsExpiry(t, forms)
}

func TestState_NoneAndUnavailableAreTokenFree(t *testing.T) {
	for _, s := range []State{None(), Unavailable()} {
		assertTokenFree(t, formsOf(s))
	}
}

func TestExportedFieldHolder_IsTokenFree(t *testing.T) {
	forms := formsOf(exportedHolder{
		Tok: NewToken(secretValue, fixedExpiry),
		St:  Bearer(NewToken(secretValue, fixedExpiry)),
	})
	assertTokenFree(t, forms)
	assertContainsExpiry(t, forms)
}

func TestUnexportedFieldHolder_IsTokenFree(t *testing.T) {
	assertTokenFree(t, formsOf(unexportedHolder{
		tok: NewToken(secretValue, fixedExpiry),
		st:  Bearer(NewToken(secretValue, fixedExpiry)),
	}))
}
