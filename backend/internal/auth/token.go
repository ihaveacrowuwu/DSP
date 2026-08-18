package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"

	"muraka/backend/internal/domain"
)

var ErrInvalidToken = errors.New("invalid or expired token")

// Claims is the access-token payload. Role is embedded so route guards need no
// database round-trip.
type Claims struct {
	jwt.RegisteredClaims
	Role domain.Role `json:"role"`
}

type TokenIssuer struct {
	secret     []byte
	issuer     string
	accessTTL  time.Duration
	refreshTTL time.Duration
}

func NewTokenIssuer(secret []byte, issuer string, accessTTL, refreshTTL time.Duration) *TokenIssuer {
	return &TokenIssuer{secret: secret, issuer: issuer, accessTTL: accessTTL, refreshTTL: refreshTTL}
}

func (t *TokenIssuer) AccessTTL() time.Duration  { return t.accessTTL }
func (t *TokenIssuer) RefreshTTL() time.Duration { return t.refreshTTL }

// IssueAccessToken returns a signed JWT for the user.
func (t *TokenIssuer) IssueAccessToken(userID uuid.UUID, role domain.Role) (string, time.Time, error) {
	now := time.Now().UTC()
	expiresAt := now.Add(t.accessTTL)

	claims := Claims{
		RegisteredClaims: jwt.RegisteredClaims{
			Subject:   userID.String(),
			Issuer:    t.issuer,
			IssuedAt:  jwt.NewNumericDate(now),
			NotBefore: jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(expiresAt),
			ID:        uuid.NewString(),
		},
		Role: role,
	}

	signed, err := jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString(t.secret)
	if err != nil {
		return "", time.Time{}, fmt.Errorf("sign token: %w", err)
	}
	return signed, expiresAt, nil
}

// ParseAccessToken validates the signature and expiry, returning the claims.
func (t *TokenIssuer) ParseAccessToken(raw string) (*Claims, error) {
	claims := &Claims{}
	token, err := jwt.ParseWithClaims(raw, claims, func(*jwt.Token) (any, error) {
		return t.secret, nil
	},
		jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}),
		jwt.WithIssuer(t.issuer),
	)
	if err != nil || !token.Valid {
		return nil, ErrInvalidToken
	}
	if _, err := uuid.Parse(claims.Subject); err != nil {
		return nil, ErrInvalidToken
	}
	return claims, nil
}

// NewRefreshToken returns an opaque token for the client plus the SHA-256 hash
// to persist. Only the hash is stored, so a database leak cannot mint sessions.
func NewRefreshToken() (token string, hash string, err error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", "", fmt.Errorf("generate refresh token: %w", err)
	}
	token = base64.RawURLEncoding.EncodeToString(buf)
	return token, HashRefreshToken(token), nil
}

func HashRefreshToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}
