package com.eia.directclaims.expert;

import java.math.BigDecimal;

/**
 * Internal command to propose a settlement. Amount is a decimal string in JSON.
 */
public record SettlementRequest(
        BigDecimal amount,
        String currency,
        String structure,
        String releaseScope,
        String paymentInstructions
) {
}
