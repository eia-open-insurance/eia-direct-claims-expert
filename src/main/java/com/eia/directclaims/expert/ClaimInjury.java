package com.eia.directclaims.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Injury allegation from SPEC §11.4.4 {@code claim_injury}. The second slice
 * records claimant, injury type, body part, ICD-10 diagnosis codes, severity,
 * and the row version used for optimistic concurrency.
 */
public record ClaimInjury(
        UUID id,
        UUID tenantId,
        UUID claimId,
        UUID claimantPartyId,
        String injuryType,
        String bodyPart,
        List<String> diagnosisCodes,
        String description,
        String severity,
        LocalDate diagnosisDate,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
    public ClaimInjury {
        diagnosisCodes = diagnosisCodes == null ? List.of() : List.copyOf(diagnosisCodes);
    }
}
