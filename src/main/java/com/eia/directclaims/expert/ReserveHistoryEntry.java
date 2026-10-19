package com.eia.directclaims.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Append-only reserve history row from SPEC §11.4.4 {@code claim_reserve}.
 * {@code isCurrent} marks the one live row per claim/component; the current
 * reserve is the sum of those rows, never an overwritten claim field.
 */
public record ReserveHistoryEntry(
        UUID id,
        UUID tenantId,
        UUID claimId,
        String component,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        LocalDate effectiveDate,
        String reasonCode,
        String reasonText,
        String authority,
        int sequence,
        boolean current,
        int version,
        Instant createdAt
) {
}
