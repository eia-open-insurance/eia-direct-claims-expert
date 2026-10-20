package com.eia.directclaims.expert;

/**
 * Outcome of an idempotent task or note create: the entity, the exact JSON body
 * to return, whether this call replayed a stored response, and whether the
 * caller supplied an Idempotency-Key at all.
 */
record ClaimCommandResult<T>(T value, String body, boolean replayed, boolean idempotent) {
}
