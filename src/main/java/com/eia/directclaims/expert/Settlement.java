package com.eia.directclaims.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Settlement from SPEC §11.4.4 {@code claim_settlement}. Amounts are decimal
 * strings. A settlement is PROPOSED, then AUTHORIZED against the claim's
 * settlement authority, then EXECUTED once the release and payment instructions
 * are recorded.
 */
public record Settlement(
        UUID id,
        UUID tenantId,
        UUID claimId,
        String settlementNumber,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        String structure,
        String releaseScope,
        String approvalAuthority,
        String paymentInstructions,
        UUID releaseDocumentId,
        String status,
        UUID authorizedBy,
        Instant authorizedAt,
        Instant executedAt,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
