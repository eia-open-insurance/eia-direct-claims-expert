package com.eia.directclaims.expert;

import java.util.UUID;

/**
 * Internal settlement authorization command. {@code approvalAuthority} names
 * the authority that approved the amount and is required.
 */
public record SettlementApprovalRequest(
        String approvalAuthority,
        UUID authorizedBy,
        Integer expectedVersion
) {
}
