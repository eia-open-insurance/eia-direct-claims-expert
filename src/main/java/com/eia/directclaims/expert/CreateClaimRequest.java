package com.eia.directclaims.expert;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateClaimRequest(
        @NotNull UUID tenantId,
        @NotNull UUID policyId,
        @NotNull UUID claimantPartyId,
        @NotNull LocalDate lossDate,
        @NotNull LocalDate reportDate,
        @Size(max = 24) String causeCode,
        @DecimalMin(value = "0.00", message = "reserveAmount must be greater than or equal to 0")
        BigDecimal reserveAmount,
        @Pattern(regexp = "[A-Z]{3}", message = "reserveCurrency must be a three-letter ISO currency code")
        String reserveCurrency,
        String status
) {
}
