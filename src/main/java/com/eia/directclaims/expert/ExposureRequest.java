package com.eia.directclaims.expert;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Internal command to record or update an exposure. {@code expectedVersion} is
 * required by PATCH and ignored by create.
 */
public record ExposureRequest(
        UUID claimantPartyId,
        String exposureType,
        String productName,
        String substanceName,
        String siteName,
        UUID employerPartyId,
        String jobTitle,
        String jurisdiction,
        LocalDate exposureFrom,
        LocalDate exposureTo,
        String intensity,
        Integer expectedVersion
) {
}
