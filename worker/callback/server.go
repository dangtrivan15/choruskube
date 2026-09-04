package callback

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"time"
)

// shutdownTimeout bounds how long Shutdown waits for in-flight callbacks to finish before
// forcing the listener closed.
const shutdownTimeout = 10 * time.Second

// Server is the Worker's HTTP callback server: the endpoints agent pods call to report
// completion and heartbeats.
type Server struct {
	httpServer *http.Server
}

// NewServer builds a Server listening on port, routing /api/v1/callback and /api/v1/heartbeat to
// handler and heartbeat respectively.
func NewServer(port int, handler *Handler, heartbeat *HeartbeatHandler) *Server {
	mux := http.NewServeMux()
	mux.Handle("/api/v1/callback", handler)
	mux.Handle("/api/v1/heartbeat", heartbeat)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	return &Server{
		httpServer: &http.Server{
			Addr:    fmt.Sprintf(":%d", port),
			Handler: mux,
		},
	}
}

// Start blocks serving until the server is shut down, or fails to bind. Callers run it in its
// own goroutine.
func (s *Server) Start() error {
	slog.Info("callback server starting", "addr", s.httpServer.Addr)
	if err := s.httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		return fmt.Errorf("callback server: %w", err)
	}
	return nil
}

// Shutdown gracefully stops the server, bounded by shutdownTimeout regardless of ctx's own
// deadline — an agent pod stuck mid-callback must not block process exit indefinitely.
func (s *Server) Shutdown(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, shutdownTimeout)
	defer cancel()
	return s.httpServer.Shutdown(ctx)
}
