package com.eia.directclaims.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Assign or reassign a claim to an adjuster and, optionally, a team.
 *
 * <p>{@code expectedVersion} is the claim's current row version and is required:
 * assignment is an optimistic command, so a stale version is rejected with 409
 * instead of overwriting a concurrent change.</p>
 */
public record ClaimAssignmentRequest(
        @NotBlank @Size(max = 128) String assignedAdjuster,
        @Size(max = 64) String assignedTeam,
        @NotNull Integer expectedVersion
) {
}
