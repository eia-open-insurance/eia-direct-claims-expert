package com.eia.directclaims.expert;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Internal command to establish or change one reserve component. The current
 * version of the claim is required so a stale change is rejected rather than
 * closing the wrong current row.
 */
public record ReserveChangeRequest(
        String component,
        BigDecimal amount,
        String currency,
        LocalDate effectiveDate,
        String reasonCode,
        String reasonText,
        String authority,
        Integer expectedVersion
) {
}
