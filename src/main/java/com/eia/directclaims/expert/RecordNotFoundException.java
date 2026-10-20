package com.eia.directclaims.expert;

import java.util.UUID;

/**
 * Raised when a child record (injury, exposure, reserve, coverage, litigation,
 * settlement, recovery, lien, task, or note) does not exist for the claim.
 * Mapped to 404.
 */
public class RecordNotFoundException extends RuntimeException {
    public RecordNotFoundException(String message) {
        super(message);
    }

    public static RecordNotFoundException of(String type, UUID id) {
        return new RecordNotFoundException(type + " not found: " + id);
    }
}
