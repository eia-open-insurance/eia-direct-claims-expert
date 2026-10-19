package com.eia.directclaims.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Claim aggregate root for the DIRECT book.
 *
 * <p>Core fields mirror SPEC §11.4.4 and the first vertical slice. The
 * {@code businessBook} value is always {@code DIRECT}; the database enforces
 * the same value with a check constraint.</p>
 */
public record Claim(
        UUID id,
        UUID tenantId,
        String businessBook,
        String claimNumber,
        UUID policyId,
        UUID claimantPartyId,
        String status,
        LocalDate lossDate,
        LocalDate reportDate,
        String causeCode,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reserveAmount,
        String reserveCurrency,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
