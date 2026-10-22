package com.eia.directclaims.expert;

import java.util.UUID;

/**
 * Sets or clears the Payment record linked to an authorized or executed
 * settlement.
 *
 * <p>A non-null {@code paymentId} links that external Payment aggregate and
 * stamps {@code paymentLinkedAt}. A null {@code paymentId} unlinks the payment
 * and clears both fields.</p>
 */
public record SettlementPaymentRequest(
        UUID paymentId
) {
}
