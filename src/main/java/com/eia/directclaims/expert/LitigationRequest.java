package com.eia.directclaims.expert;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Internal command to record or update a litigation matter. {@code damagesSought}
 * and {@code damagesCurrency} are optional on create, but must be supplied
 * together and the amount must be greater than zero. A PATCH omits both fields
 * to keep the recorded damages unchanged.
 */
public record LitigationRequest(
        String matterType,
        String jurisdiction,
        UUID counselPartyId,
        LocalDate filingDate,
        LocalDate trialDate,
        String status,
        String caseNumber,
        BigDecimal damagesSought,
        String damagesCurrency,
        Integer expectedVersion
) {
}
