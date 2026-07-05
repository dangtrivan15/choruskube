package prompt

import (
	"fmt"
	"regexp"
	"strings"
)

// varPattern matches {variable.name} style placeholders.
// It uses a named capture group for the variable key.
var varPattern = regexp.MustCompile(`\{([a-zA-Z_][a-zA-Z0-9_.]*)\}`)

// Resolver resolves prompt templates by substituting {variable} placeholders
// with values from a provided map. Double-brace sequences ({{...}}) are treated
// as literal text and are not substituted.
type Resolver struct{}

// NewResolver returns a new Resolver instance.
func NewResolver() *Resolver {
	return &Resolver{}
}

// Resolve substitutes all {variable} placeholders in template with the
// corresponding values from vars. Double-brace patterns like {{not_a_var}}
// are treated as literal text and left unchanged. Returns an error listing
// any variables that were referenced in the template but not present in vars.
func (r *Resolver) Resolve(template string, vars map[string]string) (string, error) {
	var missingVars []string

	// Protect double-brace sequences by replacing them with sentinels that
	// contain no braces, so the variable regex cannot match inside them.
	// We use two separate sentinels for {{ and }} to allow correct restoration.
	const openSentinel = "\x00OPEN\x00"
	const closeSentinel = "\x00CLOSE\x00"
	protected := strings.ReplaceAll(template, "{{", openSentinel)
	protected = strings.ReplaceAll(protected, "}}", closeSentinel)

	// Now substitute single-brace variables — double braces are already hidden.
	result := varPattern.ReplaceAllStringFunc(protected, func(match string) string {
		key := match[1 : len(match)-1]
		if val, ok := vars[key]; ok {
			return val
		}
		missingVars = append(missingVars, key)
		return match
	})

	// Restore double-brace sequences.
	result = strings.ReplaceAll(result, openSentinel, "{{")
	result = strings.ReplaceAll(result, closeSentinel, "}}")

	if len(missingVars) > 0 {
		return "", fmt.Errorf("unresolved variables: %v", missingVars)
	}
	return result, nil
}
