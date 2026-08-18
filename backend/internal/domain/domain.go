// Package domain holds the shared vocabulary of the system: enums that mirror
// the PostgreSQL types and the structs the API speaks in.
package domain

import (
	"time"

	"github.com/google/uuid"
)

// ---------------------------------------------------------------- enums

type Role string

const (
	RoleContributor Role = "contributor"
	RoleResearcher  Role = "researcher"
	RoleAdmin       Role = "admin"
)

func (r Role) Valid() bool {
	switch r {
	case RoleContributor, RoleResearcher, RoleAdmin:
		return true
	}
	return false
}

// CanVerify reports whether the role may act on the verification queue.
func (r Role) CanVerify() bool { return r == RoleResearcher || r == RoleAdmin }

type Condition string

const (
	ConditionHealthy  Condition = "healthy"
	ConditionBleached Condition = "bleached"
)

func (c Condition) Valid() bool {
	return c == ConditionHealthy || c == ConditionBleached
}

type LocationSource string

const (
	LocationGPS    LocationSource = "gps"
	LocationManual LocationSource = "manual_pin"
)

func (l LocationSource) Valid() bool {
	return l == LocationGPS || l == LocationManual
}

type SightingStatus string

const (
	StatusPendingPhotos        SightingStatus = "pending_photos"
	StatusProcessing           SightingStatus = "processing"
	StatusAwaitingVerification SightingStatus = "awaiting_verification"
	StatusVerified             SightingStatus = "verified"
	StatusRejected             SightingStatus = "rejected"
)

type Decision string

const (
	DecisionConfirmed Decision = "confirmed"
	DecisionCorrected Decision = "corrected"
	DecisionRejected  Decision = "rejected"
)

func (d Decision) Valid() bool {
	switch d {
	case DecisionConfirmed, DecisionCorrected, DecisionRejected:
		return true
	}
	return false
}

type RejectReason string

const (
	ReasonBlurry    RejectReason = "blurry"
	ReasonNotCoral  RejectReason = "not_coral"
	ReasonDuplicate RejectReason = "duplicate"
	ReasonSpam      RejectReason = "spam"
	ReasonOther     RejectReason = "other"
)

func (r RejectReason) Valid() bool {
	switch r {
	case ReasonBlurry, ReasonNotCoral, ReasonDuplicate, ReasonSpam, ReasonOther:
		return true
	}
	return false
}

// ---------------------------------------------------------------- entities

// AnonymisedUserID owns sightings whose contributor deleted their account.
// Scientific records survive; the link to the person does not.
var AnonymisedUserID = uuid.MustParse("00000000-0000-0000-0000-000000000000")

type User struct {
	ID          uuid.UUID `json:"id"`
	Email       string    `json:"email"`
	DisplayName string    `json:"displayName"`
	Role        Role      `json:"role"`
	Status      string    `json:"status"`
	CreatedAt   time.Time `json:"createdAt"`
}

type Point struct {
	Lat float64 `json:"lat"`
	Lon float64 `json:"lon"`
}

func (p Point) Valid() bool {
	return p.Lat >= -90 && p.Lat <= 90 && p.Lon >= -180 && p.Lon <= 180
}

type Sighting struct {
	ID                    uuid.UUID      `json:"id"`
	ContributorID         uuid.UUID      `json:"contributorId"`
	ContributorName       string         `json:"contributorName,omitempty"`
	SiteID                *uuid.UUID     `json:"siteId,omitempty"`
	SiteName              *string        `json:"siteName,omitempty"`
	Location              Point          `json:"location"`
	LocationSource        LocationSource `json:"locationSource"`
	LocationAccuracyM     *float64       `json:"locationAccuracyM,omitempty"`
	DepthM                *float64       `json:"depthM,omitempty"`
	CapturedAt            time.Time      `json:"capturedAt"`
	Note                  *string        `json:"note,omitempty"`
	SelfAssessedCondition *Condition     `json:"selfAssessedCondition,omitempty"`
	Status                SightingStatus `json:"status"`
	CreatedAt             time.Time      `json:"createdAt"`

	PhotoCount int `json:"photoCount"`

	// Effective label: verified wins over predicted. Nil until analysis lands.
	Condition  *Condition `json:"condition,omitempty"`
	Severity   *float64   `json:"severity,omitempty"`
	Confidence *float64   `json:"confidence,omitempty"`
	Verified   bool       `json:"verified"`
}

type Photo struct {
	ID         uuid.UUID   `json:"id"`
	SightingID uuid.UUID   `json:"sightingId"`
	URL        string      `json:"url"`
	Width      int         `json:"width"`
	Height     int         `json:"height"`
	Bytes      int         `json:"bytes"`
	CreatedAt  time.Time   `json:"createdAt"`
	Prediction *Prediction `json:"prediction,omitempty"`
}

// Patch is one cell of the inference grid, used to draw the overlay.
type Patch struct {
	Row        int       `json:"row"`
	Col        int       `json:"col"`
	Label      Condition `json:"label"`
	Confidence float64   `json:"confidence"`
}

type Prediction struct {
	ID           uuid.UUID `json:"id"`
	PhotoID      uuid.UUID `json:"photoId"`
	ModelVersion string    `json:"modelVersion"`
	Label        Condition `json:"label"`
	Confidence   float64   `json:"confidence"`
	Severity     float64   `json:"severity"`
	PatchGrid    int       `json:"patchGrid"`
	Patches      []Patch   `json:"patches"`
	InferenceMS  *int      `json:"inferenceMs,omitempty"`
	CreatedAt    time.Time `json:"createdAt"`
}

type Verification struct {
	ID           uuid.UUID     `json:"id"`
	SightingID   uuid.UUID     `json:"sightingId"`
	VerifierID   uuid.UUID     `json:"verifierId"`
	VerifierName string        `json:"verifierName,omitempty"`
	Decision     Decision      `json:"decision"`
	Label        *Condition    `json:"label,omitempty"`
	RejectReason *RejectReason `json:"rejectReason,omitempty"`
	Comment      *string       `json:"comment,omitempty"`
	CreatedAt    time.Time     `json:"createdAt"`
}

type Atoll struct {
	ID       uuid.UUID `json:"id"`
	Name     string    `json:"name"`
	Code     string    `json:"code"`
	Centroid Point     `json:"centroid"`
}

type ReefSite struct {
	ID      uuid.UUID  `json:"id"`
	AtollID *uuid.UUID `json:"atollId,omitempty"`
	Name    string     `json:"name"`
}

type ModelVersion struct {
	ID          uuid.UUID      `json:"id"`
	Version     string         `json:"version"`
	Task        string         `json:"task"`
	IsActive    bool           `json:"isActive"`
	Metrics     map[string]any `json:"metrics"`
	DatasetHash *string        `json:"datasetHash,omitempty"`
	Notes       *string        `json:"notes,omitempty"`
	TrainedAt   *time.Time     `json:"trainedAt,omitempty"`
	CreatedAt   time.Time      `json:"createdAt"`
}

// ContributorStats backs the "my impact" screen in the mobile apps.
type ContributorStats struct {
	Total    int `json:"total"`
	Verified int `json:"verified"`
	Pending  int `json:"pending"`
	Rejected int `json:"rejected"`
}
