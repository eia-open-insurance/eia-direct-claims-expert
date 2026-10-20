package com.eia.directclaims.expert;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A claim task (diary item) scoped to a tenant and a DIRECT-book claim.
 *
 * <p>{@code version} is the task's optimistic concurrency token. It starts at 1
 * and increments on each accepted status change. Task writes also bump the
 * parent claim version so the claim event stream stays monotonic; the task
 * version is carried in the event payload as {@code taskVersion}.</p>
 */
public record ClaimTask(
        UUID id,
        UUID tenantId,
        UUID claimId,
        String taskNumber,
        String title,
        String description,
        LocalDate dueDate,
        String priority,
        String status,
        String assignedTo,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {
}
