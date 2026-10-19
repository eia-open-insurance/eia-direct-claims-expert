package com.eia.directclaims.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Claim recovery from SPEC §11.4.4 {@code claim_recovery}. Received recoveries
 * are capped by the claim's paid loss.
 */
public record Recovery(
        UUID id,
        UUID tenantId,
        UUID claimId,
        String recoveryType,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        String source,
        String status,
        LocalDate recoveryDate,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
