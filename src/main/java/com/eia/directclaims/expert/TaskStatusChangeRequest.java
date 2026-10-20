package com.eia.directclaims.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Command to move a task to OPEN, COMPLETE, or CANCELLED. {@code version} is
 * the task version observed by the caller; a stale value is rejected with 409.
 */
public record TaskStatusChangeRequest(
        @NotBlank(message = "status is required")
        String status,
        @NotNull(message = "version is required")
        Long version
) {
}
