package com.eia.directclaims.expert;

import jakarta.validation.constraints.NotBlank;

public record StatusChangeRequest(
        @NotBlank String status,
        Integer expectedVersion
) {
}
