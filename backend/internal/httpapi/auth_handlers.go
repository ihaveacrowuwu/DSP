package httpapi

import (
	"errors"
	"net/http"
	"strings"
	"time"

	"muraka/backend/internal/auth"
	"muraka/backend/internal/domain"
	"muraka/backend/internal/store"
)

type registerRequest struct {
	Email       string `json:"email"`
	Password    string `json:"password"`
	DisplayName string `json:"displayName"`
}

type sessionResponse struct {
	AccessToken  string      `json:"accessToken"`
	RefreshToken string      `json:"refreshToken"`
	ExpiresAt    time.Time   `json:"expiresAt"`
	User         domain.User `json:"user"`
}

const minPasswordLen = 10

func (a *API) handleRegister(w http.ResponseWriter, r *http.Request) {
	var req registerRequest
	if !decodeJSON(w, r, &req) {
		return
	}

	req.Email = strings.ToLower(strings.TrimSpace(req.Email))
	req.DisplayName = strings.TrimSpace(req.DisplayName)

	fields := map[string]string{}
	if !strings.Contains(req.Email, "@") || len(req.Email) < 5 {
		fields["email"] = "must be a valid email address"
	}
	if len(req.Password) < minPasswordLen {
		fields["password"] = "must be at least 10 characters"
	}
	if req.DisplayName == "" {
		fields["displayName"] = "is required"
	}
	if len(fields) > 0 {
		writeFieldErrors(w, fields)
		return
	}

	hash, err := auth.HashPassword(req.Password)
	if err != nil {
		a.log.ErrorContext(r.Context(), "hash password", "error", err)
		writeError(w, http.StatusInternalServerError, "internal_error", "could not create account")
		return
	}

	// New accounts are always contributors; elevation is an admin action.
	user, err := a.store.CreateUser(r.Context(), req.Email, hash, req.DisplayName, domain.RoleContributor)
	if err != nil {
		if errors.Is(err, store.ErrConflict) {
			writeError(w, http.StatusConflict, "email_taken", "an account with that email already exists")
			return
		}
		a.writeStoreError(w, r, err, "user not found")
		return
	}

	a.issueSession(w, r, user)
}

type loginRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

func (a *API) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req loginRequest
	if !decodeJSON(w, r, &req) {
		return
	}

	rec, err := a.store.UserByEmail(r.Context(), strings.TrimSpace(req.Email))
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			// Same response as a wrong password: no account enumeration.
			writeError(w, http.StatusUnauthorized, "invalid_credentials", "email or password is incorrect")
			return
		}
		a.writeStoreError(w, r, err, "user not found")
		return
	}

	ok, err := auth.VerifyPassword(req.Password, rec.PasswordHash)
	if err != nil || !ok {
		writeError(w, http.StatusUnauthorized, "invalid_credentials", "email or password is incorrect")
		return
	}
	if rec.Status != "active" {
		writeError(w, http.StatusForbidden, "account_disabled", "this account is not active")
		return
	}

	a.issueSession(w, r, rec.User)
}

type refreshRequest struct {
	RefreshToken string `json:"refreshToken"`
}

func (a *API) handleRefresh(w http.ResponseWriter, r *http.Request) {
	var req refreshRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	if strings.TrimSpace(req.RefreshToken) == "" {
		writeFieldErrors(w, map[string]string{"refreshToken": "is required"})
		return
	}

	// Single-use rotation: consuming revokes the presented token.
	userID, err := a.store.ConsumeRefreshToken(r.Context(), auth.HashRefreshToken(req.RefreshToken))
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusUnauthorized, "invalid_token", "refresh token is invalid, expired or already used")
			return
		}
		a.writeStoreError(w, r, err, "token not found")
		return
	}

	user, err := a.store.UserByID(r.Context(), userID)
	if err != nil {
		a.writeStoreError(w, r, err, "user not found")
		return
	}
	if user.Status != "active" {
		writeError(w, http.StatusForbidden, "account_disabled", "this account is not active")
		return
	}

	a.issueSession(w, r, user)
}

func (a *API) handleLogout(w http.ResponseWriter, r *http.Request) {
	var req refreshRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	if token := strings.TrimSpace(req.RefreshToken); token != "" {
		// Best effort: an already-invalid token is still a successful logout.
		_, _ = a.store.ConsumeRefreshToken(r.Context(), auth.HashRefreshToken(token))
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *API) issueSession(w http.ResponseWriter, r *http.Request, user domain.User) {
	access, expiresAt, err := a.tokens.IssueAccessToken(user.ID, user.Role)
	if err != nil {
		a.log.ErrorContext(r.Context(), "issue access token", "error", err)
		writeError(w, http.StatusInternalServerError, "internal_error", "could not start session")
		return
	}

	refresh, refreshHash, err := auth.NewRefreshToken()
	if err != nil {
		a.log.ErrorContext(r.Context(), "generate refresh token", "error", err)
		writeError(w, http.StatusInternalServerError, "internal_error", "could not start session")
		return
	}
	if err := a.store.StoreRefreshToken(r.Context(), user.ID, refreshHash,
		time.Now().UTC().Add(a.tokens.RefreshTTL())); err != nil {
		a.writeStoreError(w, r, err, "user not found")
		return
	}

	writeJSON(w, http.StatusOK, sessionResponse{
		AccessToken:  access,
		RefreshToken: refresh,
		ExpiresAt:    expiresAt,
		User:         user,
	})
}

type meResponse struct {
	User  domain.User             `json:"user"`
	Stats domain.ContributorStats `json:"stats"`
}

func (a *API) handleMe(w http.ResponseWriter, r *http.Request) {
	user, err := a.store.UserByID(r.Context(), callerID(r))
	if err != nil {
		a.writeStoreError(w, r, err, "user not found")
		return
	}
	stats, err := a.store.ContributorStats(r.Context(), user.ID)
	if err != nil {
		a.writeStoreError(w, r, err, "user not found")
		return
	}
	writeJSON(w, http.StatusOK, meResponse{User: user, Stats: stats})
}

// handleDeleteMe anonymises the account: sightings survive as scientific record,
// the personal link does not (NFR15).
func (a *API) handleDeleteMe(w http.ResponseWriter, r *http.Request) {
	userID := callerID(r)
	if err := a.store.AnonymiseContributor(r.Context(), userID); err != nil {
		a.writeStoreError(w, r, err, "user not found")
		return
	}
	a.store.WriteAudit(r.Context(), &userID, "user.anonymised", userID.String(), nil)
	w.WriteHeader(http.StatusNoContent)
}
