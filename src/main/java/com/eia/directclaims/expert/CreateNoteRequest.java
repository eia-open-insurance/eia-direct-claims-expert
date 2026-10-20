package com.eia.directclaims.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Command to append a diary note. {@code noteType} is one of DIARY, COVERAGE,
 * RESERVE, LITIGATION, SETTLEMENT, GENERAL. Notes cannot be changed after they
 * are written.
 */
public record CreateNoteRequest(
        @NotBlank(message = "noteType is required")
        String noteType,
        @NotBlank(message = "body is required")
        @Size(max = 8000, message = "body must be at most 8000 characters")
        String body,
        @NotBlank(message = "author is required")
        @Size(max = 120, message = "author must be at most 120 characters")
        String author
) {
}
