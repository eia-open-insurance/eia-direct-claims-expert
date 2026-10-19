package com.eia.directclaims.expert;

import java.util.UUID;

/**
 * Internal settlement execution command. A release document and payment
 * instructions are required before the settlement can be executed.
 */
public record SettlementExecutionRequest(
        UUID releaseDocumentId,
        String paymentInstructions,
        Integer expectedVersion
) {
}
