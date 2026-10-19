package com.eia.directclaims.expert;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Internal command to record a final coverage decision. Recording a decision
 * supersedes the previous final position for the claim.
 */
public record CoverageDecisionRequest(
        String position,
        String basis,
        String policyProvisions,
        String allocationMethod,
        BigDecimal allocationPct,
        LocalDate decisionDate,
        UUID reviewerPartyId
) {
}
