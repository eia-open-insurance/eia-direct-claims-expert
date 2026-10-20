package com.eia.directclaims.expert;

import jakarta.validation.constraints.NotBlank;

/**
 * Command to record the policy-erosion posting outcome of an allocation.
 * {@code status} is {@code POSTED} or {@code FAILED}; {@code message} is an
 * optional posting reference or failure reason.
 */
public record AllocationErosionRequest(
        @NotBlank(message = "status is required")
        String status,
        String message
) {
}
