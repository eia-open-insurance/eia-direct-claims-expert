package com.eia.directclaims.expert;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdempotencyKeysTest {
    private static final UUID CLAIM_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d21");

    @Test
    void normalizesMissingBlankAndTrimmedKeys() {
        assertNull(IdempotencyKeys.normalize(null));
        assertNull(IdempotencyKeys.normalize("   "));
        assertEquals("key-1", IdempotencyKeys.normalize("  key-1  "));
        assertEquals("a".repeat(64), IdempotencyKeys.normalize("a".repeat(64)));
    }

    @Test
    void rejectsKeysLongerThan64Characters() {
        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> IdempotencyKeys.normalize("a".repeat(65)));

        assertTrue(error.getMessage().contains("at most 64"));
    }

    @Test
    void rejectsKeysWithSpacesOrNonPrintableCharacters() {
        assertThrows(ClaimValidationException.class, () -> IdempotencyKeys.normalize("task key"));
        assertThrows(ClaimValidationException.class, () -> IdempotencyKeys.normalize("task-key-é"));
        assertThrows(ClaimValidationException.class, () -> IdempotencyKeys.normalize("task\tkey"));
    }

    @Test
    void taskHashIgnoresCaseAndSurroundingWhitespace() {
        CreateTaskRequest first = new CreateTaskRequest(" Review records ", "  ",
                LocalDate.of(2026, 4, 15), "high", " adjuster@example.com ");
        CreateTaskRequest second = new CreateTaskRequest("Review records", null,
                LocalDate.of(2026, 4, 15), "HIGH", "adjuster@example.com");

        assertEquals(IdempotencyKeys.taskHash(CLAIM_ID, first),
                IdempotencyKeys.taskHash(CLAIM_ID, second));
    }

    @Test
    void taskHashChangesWithTheClaimOrAnyBusinessField() {
        CreateTaskRequest base = new CreateTaskRequest("Review records", null,
                LocalDate.of(2026, 4, 15), "HIGH", null);
        String hash = IdempotencyKeys.taskHash(CLAIM_ID, base);

        assertNotEquals(hash, IdempotencyKeys.taskHash(UUID.randomUUID(), base));
        assertNotEquals(hash, IdempotencyKeys.taskHash(CLAIM_ID,
                new CreateTaskRequest("Review records", null, LocalDate.of(2026, 4, 16),
                        "HIGH", null)));
        assertNotEquals(hash, IdempotencyKeys.taskHash(CLAIM_ID,
                new CreateTaskRequest("Review records", null, LocalDate.of(2026, 4, 15),
                        "LOW", null)));
    }

    @Test
    void noteHashIgnoresTypeCaseAndWhitespaceButCoversTheBodyAndAuthor() {
        CreateNoteRequest first = new CreateNoteRequest(" diary ", "Called the claimant",
                " adjuster@example.com ");
        CreateNoteRequest second = new CreateNoteRequest("DIARY", " Called the claimant ",
                "adjuster@example.com");

        assertEquals(IdempotencyKeys.noteHash(CLAIM_ID, first),
                IdempotencyKeys.noteHash(CLAIM_ID, second));
        assertNotEquals(IdempotencyKeys.noteHash(CLAIM_ID, first),
                IdempotencyKeys.noteHash(CLAIM_ID,
                        new CreateNoteRequest("DIARY", "Different note", "adjuster@example.com")));
        assertNotEquals(IdempotencyKeys.noteHash(CLAIM_ID, first),
                IdempotencyKeys.noteHash(CLAIM_ID,
                        new CreateNoteRequest("DIARY", "Called the claimant", "someone@example.com")));
    }
}
