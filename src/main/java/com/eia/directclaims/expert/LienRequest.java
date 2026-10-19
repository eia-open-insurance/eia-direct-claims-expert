package com.eia.directclaims.expert;

import java.math.BigDecimal;

/**
 * Internal command to record a lien, or to update its status and resolution.
 */
public record LienRequest(
        String lienType,
        String lienholder,
        BigDecimal amount,
        String currency,
        String status,
        String resolutionType,
        Integer expectedVersion
) {
}
