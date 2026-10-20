package com.eia.directclaims.expert;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Command to allocate part of a claim to a policy year and layer. The
 * {@code policyId} must match the claim's policy; validation lives in
 * {@link ClaimAllocationService} so the error codes stay stable.
 */
public record CreateClaimAllocationRequest(
        UUID policyId,
        Integer policyYear,
        String layer,
        BigDecimal allocationPct,
        BigDecimal allocatedAmount
) {
}
