package auth

import (
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"muraka/backend/internal/domain"
)

// ---------------------------------------------------------------- passwords

func TestHashPasswordProducesVerifiableArgon2idHash(t *testing.T) {
	hash, err := HashPassword("correct horse battery staple")
	if err != nil {
		t.Fatalf("HashPassword: %v", err)
	}
	if !strings.HasPrefix(hash, "$argon2id$") {
		t.Errorf("hash should declare argon2id, got %q", hash)
	}

	ok, err := VerifyPassword("correct horse battery staple", hash)
	if err != nil {
		t.Fatalf("VerifyPassword: %v", err)
	}
	if !ok {
		t.Error("correct password should verify")
	}
}

func TestVerifyPasswordRejectsWrongPassword(t *testing.T) {
	hash, err := HashPassword("the-real-password")
	if err != nil {
		t.Fatalf("HashPassword: %v", err)
	}

	ok, err := VerifyPassword("not-the-password", hash)
	if err != nil {
		t.Fatalf("VerifyPassword: %v", err)
	}
	if ok {
		t.Error("wrong password must not verify")
	}
}

func TestHashPasswordIsSaltedPerCall(t *testing.T) {
	// Identical passwords must not share a digest, or a leaked table would
	// reveal which accounts use the same password.
	first, err := HashPassword("same-password-both-times")
	if err != nil {
		t.Fatalf("HashPassword: %v", err)
	}
	second, err := HashPassword("same-password-both-times")
	if err != nil {
		t.Fatalf("HashPassword: %v", err)
	}

	if first == second {
		t.Error("two hashes of the same password must differ")
	}
}

func TestVerifyPasswordRejectsMalformedHashes(t *testing.T) {
	cases := map[string]string{
		"empty":           "",
		"too few fields":  "$argon2id$v=19$m=65536",
		"wrong algorithm": "$argon2i$v=19$m=65536,t=3,p=2$c2FsdA$aGFzaA",
		"bad base64":      "$argon2id$v=19$m=65536,t=3,p=2$!!!!$!!!!",
	}

	for name, hash := range cases {
		t.Run(name, func(t *testing.T) {
			ok, err := VerifyPassword("anything", hash)
			if ok {
				t.Error("malformed hash must never verify")
			}
			if err == nil {
				t.Error("malformed hash should report an error")
			}
		})
	}
}

// ---------------------------------------------------------------- tokens

func newIssuer() *TokenIssuer {
	return NewTokenIssuer([]byte("test-secret-at-least-16-bytes"), "muraka-test", 15*time.Minute, time.Hour)
}

func TestAccessTokenRoundTripCarriesSubjectAndRole(t *testing.T) {
	issuer := newIssuer()
	userID := uuid.New()

	token, expiresAt, err := issuer.IssueAccessToken(userID, domain.RoleResearcher)
	if err != nil {
		t.Fatalf("IssueAccessToken: %v", err)
	}
	if !expiresAt.After(time.Now()) {
		t.Error("token should expire in the future")
	}

	claims, err := issuer.ParseAccessToken(token)
	if err != nil {
		t.Fatalf("ParseAccessToken: %v", err)
	}
	if claims.Subject != userID.String() {
		t.Errorf("subject = %q, want %q", claims.Subject, userID)
	}
	// The role travels in the token so route guards need no database round-trip.
	if claims.Role != domain.RoleResearcher {
		t.Errorf("role = %q, want researcher", claims.Role)
	}
}

func TestParseAccessTokenRejectsForeignSignature(t *testing.T) {
	token, _, err := newIssuer().IssueAccessToken(uuid.New(), domain.RoleAdmin)
	if err != nil {
		t.Fatalf("IssueAccessToken: %v", err)
	}

	other := NewTokenIssuer([]byte("a-completely-different-secret"), "muraka-test", time.Minute, time.Hour)
	if _, err := other.ParseAccessToken(token); err == nil {
		t.Error("a token signed with another secret must be rejected")
	}
}

func TestParseAccessTokenRejectsExpiredToken(t *testing.T) {
	expired := NewTokenIssuer([]byte("test-secret-at-least-16-bytes"), "muraka-test", -time.Minute, time.Hour)

	token, _, err := expired.IssueAccessToken(uuid.New(), domain.RoleContributor)
	if err != nil {
		t.Fatalf("IssueAccessToken: %v", err)
	}
	if _, err := expired.ParseAccessToken(token); err == nil {
		t.Error("an expired token must be rejected")
	}
}

func TestParseAccessTokenRejectsWrongIssuer(t *testing.T) {
	token, _, err := newIssuer().IssueAccessToken(uuid.New(), domain.RoleContributor)
	if err != nil {
		t.Fatalf("IssueAccessToken: %v", err)
	}

	elsewhere := NewTokenIssuer([]byte("test-secret-at-least-16-bytes"), "somewhere-else", time.Minute, time.Hour)
	if _, err := elsewhere.ParseAccessToken(token); err == nil {
		t.Error("a token from another issuer must be rejected")
	}
}

func TestParseAccessTokenRejectsGarbage(t *testing.T) {
	issuer := newIssuer()
	for _, raw := range []string{"", "not.a.jwt", "aaa.bbb.ccc"} {
		if _, err := issuer.ParseAccessToken(raw); err == nil {
			t.Errorf("ParseAccessToken(%q) should fail", raw)
		}
	}
}

func TestRefreshTokenStoresOnlyItsHash(t *testing.T) {
	token, hash, err := NewRefreshToken()
	if err != nil {
		t.Fatalf("NewRefreshToken: %v", err)
	}

	if token == "" || hash == "" {
		t.Fatal("both token and hash should be produced")
	}
	// A database leak must not yield usable refresh tokens.
	if token == hash {
		t.Error("the stored hash must not equal the client token")
	}
	if got := HashRefreshToken(token); got != hash {
		t.Errorf("HashRefreshToken is not stable: %q vs %q", got, hash)
	}
}

func TestRefreshTokensAreUnique(t *testing.T) {
	seen := make(map[string]bool, 64)
	for range 64 {
		token, _, err := NewRefreshToken()
		if err != nil {
			t.Fatalf("NewRefreshToken: %v", err)
		}
		if seen[token] {
			t.Fatal("refresh tokens must not repeat")
		}
		seen[token] = true
	}
}
