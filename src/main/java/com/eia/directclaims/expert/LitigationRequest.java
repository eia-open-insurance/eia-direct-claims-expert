package com.eia.directclaims.expert;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Internal command to record or update a litigation matter.
 */
public record LitigationRequest(
        String matterType,
        String jurisdiction,
        UUID counselPartyId,
        LocalDate filingDate,
        LocalDate trialDate,
        String status,
        String caseNumber,
        Integer expectedVersion
) {
}
