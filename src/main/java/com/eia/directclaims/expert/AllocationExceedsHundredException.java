package com.eia.directclaims.expert;

/**
 * Raised when the allocation percentages across a claim would exceed 1.
 * Mapped to 409 {@code ALLOCATION_EXCEEDS_100}.
 */
public class AllocationExceedsHundredException extends RuntimeException {
    public AllocationExceedsHundredException(String message) {
        super(message);
    }
}
