package com.eia.directclaims.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Litigation matter from SPEC §11.4.4 {@code claim_litigation}: matter type,
 * jurisdiction, counsel, filing and trial dates, status, and the damages the
 * matter seeks. Damages are optional but, when supplied, amount and currency
 * are recorded together. The amount is a decimal string (SPEC 7.6).
 */
public record LitigationMatter(
        UUID id,
        UUID tenantId,
        UUID claimId,
        String matterType,
        String jurisdiction,
        UUID counselPartyId,
        LocalDate filingDate,
        LocalDate trialDate,
        String status,
        String caseNumber,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal damagesSought,
        String damagesCurrency,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
