package prompt

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestResolve_SimpleVariable(t *testing.T) {
	r := NewResolver()
	result, err := r.Resolve("Hello {name}", map[string]string{"name": "World"})
	require.NoError(t, err)
	assert.Equal(t, "Hello World", result)
}

func TestResolve_InputVariable(t *testing.T) {
	r := NewResolver()
	result, err := r.Resolve("Read {input.spec} and implement", map[string]string{
		"input.spec": "/workspace/in/spec.md",
	})
	require.NoError(t, err)
	assert.Equal(t, "Read /workspace/in/spec.md and implement", result)
}

func TestResolve_MultipleVariables(t *testing.T) {
	r := NewResolver()
	result, err := r.Resolve("{node.label} iteration {iteration}", map[string]string{
		"node.label": "Implement",
		"iteration":  "3",
	})
	require.NoError(t, err)
	assert.Equal(t, "Implement iteration 3", result)
}

func TestResolve_NoVariables(t *testing.T) {
	r := NewResolver()
	result, err := r.Resolve("Plain text prompt", map[string]string{})
	require.NoError(t, err)
	assert.Equal(t, "Plain text prompt", result)
}

func TestResolve_UnresolvedVariable(t *testing.T) {
	r := NewResolver()
	_, err := r.Resolve("Hello {missing}", map[string]string{})
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "missing")
}

func TestResolve_CurlyBracesInText(t *testing.T) {
	r := NewResolver()
	result, err := r.Resolve("JSON: {{not_a_var}}", map[string]string{})
	require.NoError(t, err)
	assert.Equal(t, "JSON: {{not_a_var}}", result)
}
