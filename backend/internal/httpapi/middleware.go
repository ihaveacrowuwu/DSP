package httpapi

import (
	"context"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"

	"muraka/backend/internal/domain"
	"muraka/backend/internal/mlclient"
)

type ctxKey string

const (
	ctxKeyUserID ctxKey = "userID"
	ctxKeyRole   ctxKey = "role"
)

// requestID assigns every request a correlation id, reusing an inbound one so
// traces survive across the Go/Python boundary (NFR12).
func requestID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rid := r.Header.Get("X-Request-ID")
		if rid == "" {
			rid = uuid.NewString()
		}
		w.Header().Set("X-Request-ID", rid)
		ctx := context.WithValue(r.Context(), mlclient.RequestIDKey, rid)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
	bytes  int
}

func (s *statusRecorder) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
}

func (s *statusRecorder) Write(b []byte) (int, error) {
	if s.status == 0 {
		s.status = http.StatusOK
	}
	n, err := s.ResponseWriter.Write(b)
	s.bytes += n
	return n, err
}

func requestLogger(log *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()
			rec := &statusRecorder{ResponseWriter: w}
			next.ServeHTTP(rec, r)

			if rec.status == 0 {
				rec.status = http.StatusOK
			}
			rid, _ := r.Context().Value(mlclient.RequestIDKey).(string)
			level := slog.LevelInfo
			if rec.status >= 500 {
				level = slog.LevelError
			}
			log.Log(r.Context(), level, "http request",
				"method", r.Method,
				"path", r.URL.Path,
				"status", rec.status,
				"bytes", rec.bytes,
				"duration_ms", time.Since(start).Milliseconds(),
				"request_id", rid,
			)
		})
	}
}

func recoverer(log *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			defer func() {
				if rec := recover(); rec != nil {
					log.ErrorContext(r.Context(), "panic recovered",
						"panic", rec, "path", r.URL.Path)
					writeError(w, http.StatusInternalServerError, "internal_error", "unexpected server error")
				}
			}()
			next.ServeHTTP(w, r)
		})
	}
}

func cors(origins []string) func(http.Handler) http.Handler {
	allowed := make(map[string]bool, len(origins))
	for _, o := range origins {
		allowed[strings.TrimSpace(o)] = true
	}
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			origin := r.Header.Get("Origin")
			if origin != "" && (allowed[origin] || allowed["*"]) {
				w.Header().Set("Access-Control-Allow-Origin", origin)
				w.Header().Set("Vary", "Origin")
				w.Header().Set("Access-Control-Allow-Credentials", "true")
				w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Request-ID, Idempotency-Key")
				w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
				w.Header().Set("Access-Control-Max-Age", "600")
			}
			if r.Method == http.MethodOptions {
				w.WriteHeader(http.StatusNoContent)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

// requireAuth validates the bearer token and pins the caller's identity and role
// into the request context.
func (a *API) requireAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		header := r.Header.Get("Authorization")
		raw, ok := strings.CutPrefix(header, "Bearer ")
		if !ok || strings.TrimSpace(raw) == "" {
			writeError(w, http.StatusUnauthorized, "unauthorized", "missing bearer token")
			return
		}

		claims, err := a.tokens.ParseAccessToken(strings.TrimSpace(raw))
		if err != nil {
			writeError(w, http.StatusUnauthorized, "unauthorized", "invalid or expired token")
			return
		}
		userID, err := uuid.Parse(claims.Subject)
		if err != nil {
			writeError(w, http.StatusUnauthorized, "unauthorized", "malformed token subject")
			return
		}

		ctx := context.WithValue(r.Context(), ctxKeyUserID, userID)
		ctx = context.WithValue(ctx, ctxKeyRole, claims.Role)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func (a *API) requireRole(roles ...domain.Role) func(http.Handler) http.Handler {
	allowed := make(map[domain.Role]bool, len(roles))
	for _, role := range roles {
		allowed[role] = true
	}
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if !allowed[callerRole(r)] {
				writeError(w, http.StatusForbidden, "forbidden", "insufficient role for this operation")
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

func callerID(r *http.Request) uuid.UUID {
	id, _ := r.Context().Value(ctxKeyUserID).(uuid.UUID)
	return id
}

func callerRole(r *http.Request) domain.Role {
	role, _ := r.Context().Value(ctxKeyRole).(domain.Role)
	return role
}
