package com.eia.directclaims.expert;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Exposure allegation from SPEC §11.4.4 {@code claim_exposure}. Exposure dates
 * are business dates and are validated against the claim report date.
 */
public record ClaimExposure(
        UUID id,
        UUID tenantId,
        UUID claimId,
        UUID claimantPartyId,
        String exposureType,
        String productName,
        String substanceName,
        String siteName,
        UUID employerPartyId,
        String jobTitle,
        String jurisdiction,
        LocalDate exposureFrom,
        LocalDate exposureTo,
        String intensity,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
