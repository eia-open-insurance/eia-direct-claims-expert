package com.eia.directclaims.expert;

import java.util.UUID;

/**
 * Raised when a claim has no recorded coverage decision. The domain maps this
 * to a 404 so the web workbench can present an empty coverage tab.
 */
public class CoverageDecisionNotFoundException extends RuntimeException {
    public CoverageDecisionNotFoundException(UUID claimId) {
        super("No coverage decision recorded for claim " + claimId);
    }
}
