package com.eia.directclaims.expert;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Internal command to record a recovery, or to advance an existing recovery's
 * status. Received recoveries are validated against paid loss.
 */
public record RecoveryRequest(
        String recoveryType,
        BigDecimal amount,
        String currency,
        String source,
        String status,
        LocalDate recoveryDate,
        Integer expectedVersion
) {
}
