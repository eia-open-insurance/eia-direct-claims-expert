package com.eia.directclaims.expert;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Internal command to record or update an injury. {@code expectedVersion} is
 * required by PATCH and ignored by create.
 */
public record InjuryRequest(
        UUID claimantPartyId,
        String injuryType,
        String bodyPart,
        List<String> diagnosisCodes,
        String description,
        String severity,
        LocalDate diagnosisDate,
        Integer expectedVersion
) {
}
