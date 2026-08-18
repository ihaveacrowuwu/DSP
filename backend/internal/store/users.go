package store

import (
	"context"
	"time"

	"github.com/google/uuid"

	"muraka/backend/internal/domain"
)

type UserRecord struct {
	domain.User
	PasswordHash string
}

func (s *Store) CreateUser(ctx context.Context, email, passwordHash, displayName string, role domain.Role) (domain.User, error) {
	var u domain.User
	err := s.pool.QueryRow(ctx, `
		INSERT INTO app_user (email, password_hash, display_name, role)
		VALUES (lower($1), $2, $3, $4)
		RETURNING id, email, display_name, role, status, created_at`,
		email, passwordHash, displayName, role,
	).Scan(&u.ID, &u.Email, &u.DisplayName, &u.Role, &u.Status, &u.CreatedAt)
	return u, mapErr(err)
}

func (s *Store) UserByEmail(ctx context.Context, email string) (UserRecord, error) {
	var u UserRecord
	err := s.pool.QueryRow(ctx, `
		SELECT id, email, display_name, role, status, created_at, password_hash
		FROM app_user WHERE email = lower($1)`, email,
	).Scan(&u.ID, &u.Email, &u.DisplayName, &u.Role, &u.Status, &u.CreatedAt, &u.PasswordHash)
	return u, mapErr(err)
}

func (s *Store) UserByID(ctx context.Context, id uuid.UUID) (domain.User, error) {
	var u domain.User
	err := s.pool.QueryRow(ctx, `
		SELECT id, email, display_name, role, status, created_at
		FROM app_user WHERE id = $1`, id,
	).Scan(&u.ID, &u.Email, &u.DisplayName, &u.Role, &u.Status, &u.CreatedAt)
	return u, mapErr(err)
}

func (s *Store) ListUsers(ctx context.Context) ([]domain.User, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT id, email, display_name, role, status, created_at
		FROM app_user
		WHERE status <> 'anonymised'
		ORDER BY created_at DESC`)
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()

	var out []domain.User
	for rows.Next() {
		var u domain.User
		if err := rows.Scan(&u.ID, &u.Email, &u.DisplayName, &u.Role, &u.Status, &u.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

func (s *Store) UpdateUserRole(ctx context.Context, id uuid.UUID, role domain.Role) error {
	tag, err := s.pool.Exec(ctx,
		`UPDATE app_user SET role = $2, updated_at = now() WHERE id = $1 AND status <> 'anonymised'`, id, role)
	if err != nil {
		return mapErr(err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *Store) SetUserStatus(ctx context.Context, id uuid.UUID, status string) error {
	tag, err := s.pool.Exec(ctx,
		`UPDATE app_user SET status = $2, updated_at = now() WHERE id = $1 AND status <> 'anonymised'`, id, status)
	if err != nil {
		return mapErr(err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *Store) ContributorStats(ctx context.Context, userID uuid.UUID) (domain.ContributorStats, error) {
	var st domain.ContributorStats
	err := s.pool.QueryRow(ctx, `
		SELECT
			count(*),
			count(*) FILTER (WHERE status = 'verified'),
			count(*) FILTER (WHERE status IN ('pending_photos','processing','awaiting_verification')),
			count(*) FILTER (WHERE status = 'rejected')
		FROM sighting WHERE contributor_id = $1`, userID,
	).Scan(&st.Total, &st.Verified, &st.Pending, &st.Rejected)
	return st, mapErr(err)
}

// ---------------------------------------------------------------- refresh tokens

func (s *Store) StoreRefreshToken(ctx context.Context, userID uuid.UUID, tokenHash string, expiresAt time.Time) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO refresh_token (user_id, token_hash, expires_at)
		VALUES ($1, $2, $3)`, userID, tokenHash, expiresAt)
	return mapErr(err)
}

// ConsumeRefreshToken atomically revokes a live token and returns its owner,
// implementing single-use refresh-token rotation.
func (s *Store) ConsumeRefreshToken(ctx context.Context, tokenHash string) (uuid.UUID, error) {
	var userID uuid.UUID
	err := s.pool.QueryRow(ctx, `
		UPDATE refresh_token
		SET revoked_at = now()
		WHERE token_hash = $1 AND revoked_at IS NULL AND expires_at > now()
		RETURNING user_id`, tokenHash,
	).Scan(&userID)
	return userID, mapErr(err)
}

func (s *Store) RevokeUserRefreshTokens(ctx context.Context, userID uuid.UUID) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE refresh_token SET revoked_at = now()
		WHERE user_id = $1 AND revoked_at IS NULL`, userID)
	return mapErr(err)
}
