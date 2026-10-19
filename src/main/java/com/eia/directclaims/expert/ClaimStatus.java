package com.eia.directclaims.expert;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Direct Claims lifecycle, transcribed from the state diagram in SPEC §11.4.6.
 *
 * <p>Every transition not listed here is rejected. {@code DISMISSED} is in the
 * enumeration and can close, but the specification diagram declares no entry
 * transition into it; it is therefore not reachable through this API slice.</p>
 */
public enum ClaimStatus {
    DRAFT,
    REGISTERED,
    OPEN,
    COVERAGE_REVIEW,
    RESERVED,
    IN_LITIGATION,
    IN_SETTLEMENT,
    SETTLED,
    PAYMENT_PENDING,
    PAID,
    CLOSED,
    DENIED,
    WITHDRAWN,
    DISMISSED,
    TRANSFERRED,
    BANKRUPTCY_TRUST,
    ON_APPEAL,
    REOPENED;

    private static final Map<ClaimStatus, Set<ClaimStatus>> TRANSITIONS = new EnumMap<>(ClaimStatus.class);
    private static final Set<ClaimStatus> INITIAL = EnumSet.of(DRAFT, REGISTERED);

    static {
        transition(DRAFT, REGISTERED);
        transition(REGISTERED, OPEN);
        transition(OPEN, COVERAGE_REVIEW, RESERVED, DENIED, WITHDRAWN, TRANSFERRED, BANKRUPTCY_TRUST);
        transition(COVERAGE_REVIEW, OPEN);
        transition(RESERVED, IN_LITIGATION, IN_SETTLEMENT, DENIED);
        transition(IN_LITIGATION, IN_SETTLEMENT, ON_APPEAL);
        transition(IN_SETTLEMENT, SETTLED);
        transition(SETTLED, PAYMENT_PENDING);
        transition(PAYMENT_PENDING, PAID);
        transition(PAID, CLOSED);
        transition(DENIED, REOPENED, CLOSED);
        transition(WITHDRAWN, CLOSED);
        transition(DISMISSED, CLOSED);
        transition(TRANSFERRED, CLOSED);
        transition(CLOSED, REOPENED);
        transition(REOPENED, OPEN);
        transition(BANKRUPTCY_TRUST, CLOSED);
    }

    private static void transition(ClaimStatus from, ClaimStatus... targets) {
        Set<ClaimStatus> allowed = TRANSITIONS.computeIfAbsent(from, key -> EnumSet.noneOf(ClaimStatus.class));
        for (ClaimStatus target : targets) {
            allowed.add(target);
        }
    }

    public boolean canTransitionTo(ClaimStatus next) {
        return next != null && TRANSITIONS.getOrDefault(this, Set.of()).contains(next);
    }

    public boolean isInitial() {
        return INITIAL.contains(this);
    }

    public static ClaimStatus from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ClaimValidationException("status is required");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ClaimValidationException("Unknown claim status: " + raw);
        }
    }
}
