package activity

import (
	"strings"
	"testing"
)

func TestResolve_SimpleVariable(t *testing.T) {
	r := newTemplateResolver()
	result, err := r.resolve("Hello {name}", map[string]string{"name": "World"})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if result != "Hello World" {
		t.Fatalf("result = %q, want %q", result, "Hello World")
	}
}

func TestResolve_InputVariable(t *testing.T) {
	r := newTemplateResolver()
	result, err := r.resolve("Read {input.spec} and implement", map[string]string{
		"input.spec": "/workspace/in/spec.md",
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	want := "Read /workspace/in/spec.md and implement"
	if result != want {
		t.Fatalf("result = %q, want %q", result, want)
	}
}

func TestResolve_MultipleVariables(t *testing.T) {
	r := newTemplateResolver()
	result, err := r.resolve("{node.label} iteration {iteration}", map[string]string{
		"node.label": "Implement",
		"iteration":  "3",
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if result != "Implement iteration 3" {
		t.Fatalf("result = %q, want %q", result, "Implement iteration 3")
	}
}

func TestResolve_NoVariables(t *testing.T) {
	r := newTemplateResolver()
	result, err := r.resolve("Plain text prompt", map[string]string{})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if result != "Plain text prompt" {
		t.Fatalf("result = %q, want %q", result, "Plain text prompt")
	}
}

func TestResolve_UnresolvedVariable(t *testing.T) {
	r := newTemplateResolver()
	_, err := r.resolve("Hello {missing}", map[string]string{})
	if err == nil {
		t.Fatal("want an error for an unresolved variable, got nil")
	}
	if !strings.Contains(err.Error(), "missing") {
		t.Fatalf("error should mention the missing variable, got %q", err.Error())
	}
}

func TestResolve_CurlyBracesInText(t *testing.T) {
	r := newTemplateResolver()
	result, err := r.resolve("JSON: {{not_a_var}}", map[string]string{})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if result != "JSON: {{not_a_var}}" {
		t.Fatalf("result = %q, want %q", result, "JSON: {{not_a_var}}")
	}
}
