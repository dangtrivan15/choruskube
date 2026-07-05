package objectstore

import (
	"context"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// fakeObjectStore is an in-memory implementation for testing consumers.
type fakeObjectStore struct {
	objects map[string][]byte
}

func newFakeObjectStore() *fakeObjectStore {
	return &fakeObjectStore{objects: make(map[string][]byte)}
}

func (f *fakeObjectStore) PutObject(_ context.Context, key string, data []byte) error {
	f.objects[key] = data
	return nil
}

func (f *fakeObjectStore) GetObject(_ context.Context, key string) ([]byte, error) {
	return f.objects[key], nil
}

func TestFakeObjectStore_MissingKeyReturnsNil(t *testing.T) {
	store := newFakeObjectStore()
	data, err := store.GetObject(context.Background(), "nonexistent")
	require.NoError(t, err)
	assert.Nil(t, data)
}

func TestFakeObjectStore_PutThenGet(t *testing.T) {
	store := newFakeObjectStore()
	err := store.PutObject(context.Background(), "test/key.md", []byte("hello"))
	require.NoError(t, err)

	data, err := store.GetObject(context.Background(), "test/key.md")
	require.NoError(t, err)
	assert.Equal(t, []byte("hello"), data)
}

func TestFakeObjectStore_PutOverwrites(t *testing.T) {
	store := newFakeObjectStore()
	store.PutObject(context.Background(), "key", []byte("v1"))
	store.PutObject(context.Background(), "key", []byte("v2"))

	data, _ := store.GetObject(context.Background(), "key")
	assert.Equal(t, []byte("v2"), data)
}
