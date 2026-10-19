package com.eia.directclaims.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Derived reserve position for a claim: one current balance per component plus
 * the full audit history. {@code totalCurrentAmount} is the sum of the current
 * rows and equals the claim's derived current reserve.
 */
public record ReserveSummary(
        UUID claimId,
        String currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalCurrentAmount,
        List<ReserveComponentBalance> components,
        List<ReserveHistoryEntry> history
) {
    public record ReserveComponentBalance(
            String component,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal currentAmount,
            String currency
    ) {
    }
}
