package com.eia.directclaims.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Coverage decision for a claim. Only one row per claim carries status
 * {@code FINAL}; recording a new decision supersedes the previous one. The
 * position set for this slice is COVERED, DENIED, and DEFENSE_ONLY.
 */
public record CoverageDecision(
        UUID id,
        UUID tenantId,
        UUID claimId,
        String position,
        String basis,
        String policyProvisions,
        String allocationMethod,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal allocationPct,
        LocalDate decisionDate,
        UUID reviewerPartyId,
        String status,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
