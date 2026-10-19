package com.eia.directclaims.expert;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Litigation matter from SPEC §11.4.4 {@code claim_litigation}: matter type,
 * jurisdiction, counsel, filing and trial dates, and status.
 */
public record LitigationMatter(
        UUID id,
        UUID tenantId,
        UUID claimId,
        String matterType,
        String jurisdiction,
        UUID counselPartyId,
        LocalDate filingDate,
        LocalDate trialDate,
        String status,
        String caseNumber,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
