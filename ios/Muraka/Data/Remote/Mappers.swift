import Foundation

/// Wire types to domain types.
///
/// This is the boundary the architecture rule protects: a `SightingDTO` must never appear
/// above `Data/`. The mapping is deliberately total - an unknown enum value falls back to a
/// sane default rather than throwing, so a server that grows a sixth status shows an
/// installed app something odd rather than crashing it.

extension UserDTO {
    var domain: User {
        User(
            id: id,
            email: email,
            displayName: displayName,
            role: Role(rawValue: role) ?? .contributor,
            status: status,
            createdAt: createdAt
        )
    }
}

extension ContributorStatsDTO {
    var domain: ContributorStats {
        ContributorStats(total: total, verified: verified, pending: pending, rejected: rejected)
    }
}

extension MeDTO {
    var domain: Profile { Profile(user: user.domain, stats: stats.domain) }
}

extension SightingDTO {
    var domain: Sighting {
        Sighting(
            id: id,
            contributorID: contributorId,
            contributorName: contributorName,
            siteName: siteName,
            position: Position(lat: location.lat, lon: location.lon),
            locationSource: LocationSource(rawValue: locationSource) ?? .gps,
            locationAccuracyM: locationAccuracyM,
            depthM: depthM,
            capturedAt: capturedAt,
            note: note,
            selfAssessedCondition: Condition(wire: selfAssessedCondition),
            // An unrecognised status is treated as "still being worked on" rather than as a
            // verdict, which is the safe direction to be wrong in.
            status: SightingStatus(rawValue: status) ?? .processing,
            createdAt: createdAt,
            photoCount: photoCount,
            condition: Condition(wire: condition),
            severity: severity,
            confidence: confidence,
            verified: verified
        )
    }
}

extension PatchDTO {
    var domain: Patch {
        Patch(row: row, col: col, label: Condition(wire: label) ?? .healthy, confidence: confidence)
    }
}

extension PredictionDTO {
    var domain: Prediction {
        Prediction(
            id: id,
            photoID: photoId,
            modelVersion: modelVersion,
            label: Condition(wire: label) ?? .healthy,
            confidence: confidence,
            severity: severity,
            patchGrid: patchGrid,
            patches: patches.map(\.domain),
            inferenceMs: inferenceMs,
            createdAt: createdAt
        )
    }
}

extension PhotoDTO {
    var domain: Photo {
        Photo(
            id: id,
            sightingID: sightingId,
            url: url,
            width: width,
            height: height,
            bytes: bytes,
            createdAt: createdAt,
            prediction: prediction?.domain
        )
    }
}

extension VerificationDTO {
    var domain: Verification {
        Verification(
            id: id,
            sightingID: sightingId,
            verifierName: verifierName,
            decision: VerificationDecision(rawValue: decision) ?? .confirmed,
            label: Condition(wire: label),
            rejectReason: rejectReason.flatMap(RejectReason.init(rawValue:)),
            comment: comment,
            createdAt: createdAt
        )
    }
}

extension SightingDetailDTO {
    var domain: SightingDetail {
        SightingDetail(
            sighting: sighting.domain,
            photos: photos.map(\.domain),
            verifications: verifications.map(\.domain)
        )
    }
}
