package com.eia.directclaims.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A policy-year and layer allocation of a claim.
 *
 * <p>{@code allocationPct} is a fraction of the claim (greater than 0 and at
 * most 1) and the fractions across one claim may not exceed 1.
 * {@code allocatedAmount} is a booking figure in the claim's currency and is
 * not capped by the current reserve. Money and percentages are serialized as
 * decimal strings (SPEC §7.6).</p>
 */
public record ClaimAllocation(
        UUID id,
        UUID tenantId,
        UUID claimId,
        UUID policyId,
        int policyYear,
        String layer,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal allocationPct,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal allocatedAmount,
        Instant createdAt
) {
}
