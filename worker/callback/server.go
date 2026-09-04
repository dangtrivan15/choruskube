package callback

import (
	"context"
	"fmt"
	"log/slog"
	"net"
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
	ln         net.Listener
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

// Listen binds the server socket. Call it before Serve so a port conflict surfaces as an error
// to the caller rather than disappearing inside a goroutine.
func (s *Server) Listen() error {
	ln, err := net.Listen("tcp", s.httpServer.Addr)
	if err != nil {
		return fmt.Errorf("callback server bind %s: %w", s.httpServer.Addr, err)
	}
	s.ln = ln
	slog.Info("callback server listening", "addr", ln.Addr())
	return nil
}

// Serve blocks accepting connections on the listener opened by Listen. Callers run it in its
// own goroutine after Listen returns successfully.
func (s *Server) Serve() error {
	if err := s.httpServer.Serve(s.ln); err != nil && err != http.ErrServerClosed {
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
