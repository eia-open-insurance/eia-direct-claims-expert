package com.eia.directclaims.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * An append-only diary note on a claim. Notes have no version and no update or
 * delete route; the row and its {@code direct.claim.note-added} event are
 * written in one transaction.
 */
public record ClaimNote(
        UUID id,
        UUID tenantId,
        UUID claimId,
        String noteNumber,
        String noteType,
        String body,
        String author,
        Instant createdAt
) {
}
