package com.eia.directclaims.expert;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Shared claim lookup for the child-resource services. Every lookup carries the
 * tenant and the DIRECT book, so a child route cannot escape the book wall.
 */
@Component
public class ClaimLookup {
    private final ClaimRepository repository;

    public ClaimLookup(ClaimRepository repository) {
        this.repository = repository;
    }

    public Claim require(UUID tenantId, UUID claimId) {
        return repository.findById(tenantId, claimId)
                .orElseThrow(() -> new ClaimNotFoundException(claimId));
    }

    /**
     * A child fact must name the claim's own claimant. The Party service owns
     * the claimant identity; this slice rejects a claimant that does not match
     * the claim so an injury or exposure cannot be attached to a stranger.
     */
    public void requireClaimant(Claim claim, UUID claimantPartyId) {
        if (claimantPartyId == null) {
            throw new ClaimValidationException("claimantPartyId is required");
        }
        if (!claimantPartyId.equals(claim.claimantPartyId())) {
            throw new ClaimValidationException(
                    "claimantPartyId " + claimantPartyId + " is not a claimant of claim " + claim.claimNumber());
        }
    }
}
