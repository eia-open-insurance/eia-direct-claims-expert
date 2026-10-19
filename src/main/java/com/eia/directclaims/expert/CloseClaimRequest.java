package com.eia.directclaims.expert;

/**
 * Optional optimistic-concurrency guard for closure.
 */
public record CloseClaimRequest(Integer expectedVersion) {
}
