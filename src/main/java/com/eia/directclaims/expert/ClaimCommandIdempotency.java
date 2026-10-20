package com.eia.directclaims.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * One stored idempotent create command: the canonical request hash and the
 * original 201 response that a replay must return.
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
