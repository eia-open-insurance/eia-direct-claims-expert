package com.eia.directclaims.expert;

import java.time.Instant;
import java.util.List;

public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        List<FieldViolation> details
) {
    public static ApiError of(int status, String code, String message, String path, List<FieldViolation> details) {
        return new ApiError(Instant.now(), status, code, message, path, details);
    }
}
