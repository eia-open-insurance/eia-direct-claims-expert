package com.eia.directclaims.expert;

/**
 * Raised when an allocation names a policy other than the claim's policy.
 * Mapped to 400 {@code POLICY_MISMATCH}.
 */
public class PolicyMismatchException extends RuntimeException {
    public PolicyMismatchException(String message) {
        super(message);
    }
}
