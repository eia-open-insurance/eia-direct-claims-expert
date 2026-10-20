package com.eia.directclaims.expert;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Idempotency support for the Direct Claims task and note create commands.
 *
 * <p>A key is trimmed, must be 1-64 printable ASCII characters, and is scoped by
 * tenant plus command type. The request hash is computed from a canonical,
 * field-ordered representation of the command, so a retry of the same business
 * request replays while a different request under the same key is rejected.</p>
 */
final class IdempotencyKeys {
    static final int MAX_LENGTH = 64;

    /** Printable ASCII without spaces. UUIDs are the recommended shape. */
    private static final Pattern SAFE_KEY = Pattern.compile("[\\x21-\\x7E]{1," + MAX_LENGTH + "}");

    private IdempotencyKeys() {
    }

    /**
     * @return the trimmed key, or {@code null} when the header is missing/blank
     * @throws ClaimValidationException when the key is longer than 64 characters
     *         or contains characters outside printable ASCII
     */
    static String normalize(String rawKey) {
        if (rawKey == null) {
            return null;
        }
        String key = rawKey.trim();
        if (key.isEmpty()) {
            return null;
        }
        if (key.length() > MAX_LENGTH) {
            throw new ClaimValidationException(
                    "Idempotency-Key must be at most " + MAX_LENGTH + " characters");
        }
        if (!SAFE_KEY.matcher(key).matches()) {
            throw new ClaimValidationException(
                    "Idempotency-Key must contain only printable ASCII characters without spaces");
        }
        return key;
    }

    static String taskHash(UUID claimId, CreateTaskRequest request) {
        return sha256("command=task-create"
                + "\nclaimId=" + claimId
                + "\ntitle=" + text(request.title())
                + "\ndescription=" + text(request.description())
                + "\ndueDate=" + (request.dueDate() == null ? "" : request.dueDate())
                + "\npriority=" + upper(request.priority())
                + "\nassignedTo=" + text(request.assignedTo()));
    }

    static String noteHash(UUID claimId, CreateNoteRequest request) {
        return sha256("command=note-create"
                + "\nclaimId=" + claimId
                + "\nnoteType=" + upper(request.noteType())
                + "\nbody=" + text(request.body())
                + "\nauthor=" + text(request.author()));
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
    }
}
