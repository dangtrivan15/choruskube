package activity

import (
	"sync"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
)

func TestPendingCache_PutGetRemove(t *testing.T) {
	c := NewPendingCache()
	id := uuid.New()

	_, ok := c.Get(id)
	assert.False(t, ok, "unpopulated cache must report a miss")

	want := PendingCompletion{Namespace: "ns", TaskQueue: "q", WorkflowID: "choruskube-run-x", ActivityID: id.String()}
	c.Put(id, want)

	got, ok := c.Get(id)
	assert.True(t, ok)
	assert.Equal(t, want, got)

	c.Remove(id)
	_, ok = c.Get(id)
	assert.False(t, ok, "removed entry must not be served on a later Get")
}

// TestPendingCache_ConcurrentAccess exercises the same contention shape production has: the
// activity goroutine populating entries while the callback server's HTTP handlers read and
// remove them concurrently. Run with -race.
func TestPendingCache_ConcurrentAccess(t *testing.T) {
	c := NewPendingCache()
	ids := make([]uuid.UUID, 50)
	for i := range ids {
		ids[i] = uuid.New()
	}

	var wg sync.WaitGroup
	for _, id := range ids {
		wg.Add(1)
		go func(id uuid.UUID) {
			defer wg.Done()
			c.Put(id, PendingCompletion{ActivityID: id.String()})
			c.Get(id)
			c.Remove(id)
		}(id)
	}
	wg.Wait()
}
