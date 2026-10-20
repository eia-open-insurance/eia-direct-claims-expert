package com.eia.directclaims.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.List;

/**
 * Claim allocations oldest first plus the sum of the allocation percentages and
 * allocated amounts across the claim. Both totals are serialized as decimal
 * strings.
 */
public record ClaimAllocationSummary(
        List<ClaimAllocation> items,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalPct,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAmount
) {
}
