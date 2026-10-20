package com.eia.directclaims.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Claim aggregate root for the DIRECT book.
 *
 * <p>{@code reserveAmount} is the reserve seeded when the claim was registered;
 * {@code currentReserveAmount} is derived from the current rows of
 * {@code claim_reserve_history} and is never written back over the seed. Money
 * is serialized as a decimal string per SPEC §7.6 and §10.3.</p>
 */
public record Claim(
        UUID id,
        UUID tenantId,
        String businessBook,
        String claimNumber,
        UUID policyId,
        UUID claimantPartyId,
        String claimType,
        String status,
        LocalDate lossDate,
        LocalDate reportDate,
        String causeCode,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reserveAmount,
        String reserveCurrency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal settlementAuthorityAmount,
        String settlementAuthorityCurrency,
        boolean litigationFlag,
        String assignedAdjuster,
        String assignedTeam,
        Instant assignedAt,
        String assignedBy,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal currentReserveAmount,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
