package activity

import (
	"sync"

	"github.com/google/uuid"
)

// PendingCompletion is what the Worker needs to complete, by ID, the Temporal activity a workload
// is blocked in. Captured from the activity's own Info at Execute() time because the agent's
// callbacks carry only a NodeExecutionID, which alone cannot address the workflow run or the
// per-Fleet Temporal connection it is polled on.
type PendingCompletion struct {
	Namespace  string
	TaskQueue  string
	WorkflowID string
	ActivityID string
}

// PendingCache holds one PendingCompletion per locally launched execution awaiting callback, keyed
// by NodeExecutionID. executeLocally populates it before returning ErrResultPending so a callback
// always finds its entry; a callback.ActivityCompleter over it reads it on the agent's POST.
type PendingCache struct {
	mu    sync.RWMutex
	store map[uuid.UUID]PendingCompletion
}

// NewPendingCache returns an empty PendingCache.
func NewPendingCache() *PendingCache {
	return &PendingCache{store: make(map[uuid.UUID]PendingCompletion)}
}

// Put records p for executionID, overwriting any existing entry.
func (c *PendingCache) Put(executionID uuid.UUID, p PendingCompletion) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.store[executionID] = p
}

// Get returns the cached PendingCompletion for executionID, and whether one was found.
func (c *PendingCache) Get(executionID uuid.UUID) (PendingCompletion, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	p, ok := c.store[executionID]
	return p, ok
}

// Remove drops the cached entry for executionID.
func (c *PendingCache) Remove(executionID uuid.UUID) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.store, executionID)
}
