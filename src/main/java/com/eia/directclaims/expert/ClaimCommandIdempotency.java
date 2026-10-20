package com.eia.directclaims.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * One stored idempotent create command. A row starts as a reservation, so
 * {@code responseStatus} and {@code responseBody} are only meaningful once the
 * command has completed; a replay reads the body the original caller stored.
 */
public record ClaimCommandIdempotency(
        UUID tenantId,
        String idempotencyKey,
        String commandType,
        String requestHash,
        int responseStatus,
        String responseBody,
        Instant createdAt
) {
}
