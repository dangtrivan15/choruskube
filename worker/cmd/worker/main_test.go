package main

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestBuildExecutor_DefaultsToDocker(t *testing.T) {
	env := map[string]string{} // EXECUTOR_TYPE unset
	typ, err := executorTypeOf(env["EXECUTOR_TYPE"])
	require.NoError(t, err)
	assert.Equal(t, "docker", typ)
}

func TestBuildExecutor_RejectsUnknownType(t *testing.T) {
	_, err := executorTypeOf("swarm")
	require.Error(t, err) // must fail loudly, not silently fall back
}

func TestBuildExecutor_K8sSelected(t *testing.T) {
	typ, err := executorTypeOf("k8s")
	require.NoError(t, err)
	assert.Equal(t, "k8s", typ)
}
