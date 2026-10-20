package com.eia.directclaims.expert;

/**
 * Raised when an Idempotency-Key is reused for a different task or note create
 * request. It maps to 409 {@code EIA-DIR-4091} / {@code IDEMPOTENCY_KEY_REUSED}.
 */
public class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException(String message) {
        super(message);
    }
}
