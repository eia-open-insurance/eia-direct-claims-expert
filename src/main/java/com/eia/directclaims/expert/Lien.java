package com.eia.directclaims.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Claim lien from SPEC §11.4.4 {@code claim_lien}. Resolution is recorded before
 * a claim can close (DIR-11).
 */
public record Lien(
        UUID id,
        UUID tenantId,
        UUID claimId,
        String lienType,
        String lienholder,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        String status,
        String resolutionType,
        Instant resolvedAt,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
