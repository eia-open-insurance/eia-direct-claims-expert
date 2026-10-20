package com.eia.directclaims.expert;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Assign or reassign a claim to an adjuster and, optionally, a team.
 *
 * <p>{@code expectedVersion} is the claim's current row version and is required:
 * assignment is an optimistic command, so a stale version is rejected with 409
 * instead of overwriting a concurrent change. A negative version can never be
 * current, so it is a 400 validation error rather than a conflict.</p>
 */
public record ClaimAssignmentRequest(
        @NotBlank @Size(max = 128) String assignedAdjuster,
        @Size(max = 64) String assignedTeam,
        @NotNull @Min(value = 0, message = "expectedVersion must be greater than or equal to 0")
        Integer expectedVersion
) {
}
