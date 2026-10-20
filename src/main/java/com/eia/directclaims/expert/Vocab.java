package com.eia.directclaims.expert;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Closed vocabularies and primitive validation shared by the second Direct
 * Claims slice. Enumerations are stored as {@code varchar} per SPEC §10.4; the
 * sets below are the allowlists the API enforces.
 */
final class Vocab {
    private Vocab() {
    }

    static final Set<String> CLAIM_TYPES = Set.of(
            "BODILY_INJURY", "PROPERTY_DAMAGE", "PRODUCT_LIABILITY", "MASS_TORT", "ENVIRONMENTAL",
            "POLLUTION", "CONSTRUCTION_DEFECT", "AUTO_LIABILITY", "TRUCKING", "WORKERS_COMPENSATION",
            "EMPLOYERS_LIABILITY", "MEDICAL_MALPRACTICE", "PROFESSIONAL_LIABILITY",
            "DIRECTORS_AND_OFFICERS", "EMPLOYMENT_PRACTICES", "CLASS_ACTION",
            "MULTI_DISTRICT_LITIGATION", "SUBROGATION", "OTHER");

    /** DIR-02: claims that cannot be accepted without an injury or exposure fact. */
    static final Set<String> INJURY_REQUIRED_TYPES = Set.of(
            "BODILY_INJURY", "PRODUCT_LIABILITY", "MASS_TORT", "ENVIRONMENTAL", "POLLUTION",
            "CONSTRUCTION_DEFECT", "AUTO_LIABILITY", "TRUCKING", "WORKERS_COMPENSATION",
            "EMPLOYERS_LIABILITY", "MEDICAL_MALPRACTICE", "PROFESSIONAL_LIABILITY",
            "DIRECTORS_AND_OFFICERS", "EMPLOYMENT_PRACTICES", "CLASS_ACTION",
            "MULTI_DISTRICT_LITIGATION", "OTHER");

    /** DIR-02: mass tort and environmental claims additionally require exposure. */
    static final Set<String> EXPOSURE_REQUIRED_TYPES = Set.of("MASS_TORT", "ENVIRONMENTAL", "POLLUTION");

    static final Set<String> INJURY_TYPES = Set.of(
            "MESOTHELIOMA", "LUNG_CANCER", "ASBESTOSIS", "PLEURAL_DISEASE", "OVARIAN_CANCER",
            "KIDNEY_CANCER", "TESTICULAR_CANCER", "BLADDER_CANCER", "NON_HODGKINS_LYMPHOMA",
            "UTERINE_CANCER", "THYROID_CANCER", "BREAST_CANCER", "HEARING_LOSS", "TINNITUS",
            "PULMONARY_EMBOLISM", "ULCERATIVE_COLITIS", "LIVER_INJURY", "NEUROLOGICAL_INJURY",
            "BIRTH_DEFECT", "AUTISM_SPECTRUM", "ADDICTION", "NEONATAL_ABSTINENCE", "OVERDOSE",
            "RESPIRATORY_INJURY", "CARDIOVASCULAR_INJURY", "ORTHOPEDIC_INJURY", "SOFT_TISSUE_INJURY",
            "PSYCHOLOGICAL_INJURY", "WRONGFUL_DEATH", "OTHER");

    static final Set<String> SEVERITIES = Set.of("MILD", "MODERATE", "SEVERE", "CATASTROPHIC", "FATAL");
    static final Set<String> EXPOSURE_TYPES = Set.of(
            "PRODUCT", "SUBSTANCE", "SITE", "OCCUPATION", "ENVIRONMENT", "MEDICAL_DEVICE", "IMPLANT", "RADIATION");
    static final Set<String> INTENSITIES = Set.of("LOW", "MEDIUM", "HIGH", "EXTREME", "UNKNOWN");

    static final Set<String> RESERVE_COMPONENTS = Set.of(
            "INDEMNITY", "DEFENSE", "ALAE", "ULAE", "EXPENSE", "MEDICAL", "SUBROGATION", "RECOVERY",
            "IBNR", "IBNER");
    static final Set<String> RESERVE_REASONS = Set.of(
            "INITIAL", "DEVELOPMENT", "NEW_INFORMATION", "SETTLEMENT_NEGOTIATION", "COVERAGE_CHANGE",
            "PAYMENT_APPLIED", "RECOVERY_APPLIED", "REVIEW", "CORRECTION", "CLOSE");

    static final Set<String> COVERAGE_POSITIONS = Set.of("COVERED", "DENIED", "DEFENSE_ONLY");
    static final Set<String> ALLOCATION_METHODS = Set.of(
            "ALL_SUMS", "PRO_RATA_TIME", "PRO_RATA_PREMIUM", "EQUAL", "CUSTOM");

    static final Set<String> MATTER_TYPES = Set.of(
            "DEFENSE", "COVERAGE", "APPEAL", "BANKRUPTCY", "REGULATORY", "TRANSACTIONAL");
    static final Set<String> LITIGATION_STATUSES = Set.of(
            "ACTIVE", "STAYED", "DISMISSED", "SETTLED", "JUDGMENT", "ON_APPEAL", "CLOSED");

    static final Set<String> SETTLEMENT_STRUCTURES = Set.of("LUMP_SUM", "STRUCTURED", "ANNUITY", "MATRIX");
    static final Set<String> SETTLEMENT_STATUSES = Set.of("PROPOSED", "AUTHORIZED", "EXECUTED", "REJECTED");
    static final Set<String> RELEASE_SCOPES = Set.of("FULL", "PARTIAL", "CLAIMANT_SPECIFIC", "POLICY_YEAR");

    static final Set<String> RECOVERY_TYPES = Set.of(
            "SUBROGATION", "SALVAGE", "CONTRIBUTION", "ASSUMED", "OTHER_INSURANCE", "INDEMNITY", "TRUST",
            "GOVERNMENT", "OTHER");
    static final Set<String> RECOVERY_STATUSES = Set.of(
            "IDENTIFIED", "PURSUING", "AGREED", "RECEIVED", "DENIED", "ABANDONED");

    static final Set<String> LIEN_TYPES = Set.of(
            "MEDICARE", "MEDICAID", "WORKERS_COMPENSATION", "HOSPITAL", "PHYSICIAN", "ERISA",
            "CHILD_SUPPORT", "TAX", "VETERANS", "OTHER");
    static final Set<String> LIEN_STATUSES = Set.of(
            "IDENTIFIED", "VERIFIED", "NEGOTIATING", "RESOLVED", "WAIVED", "DISPUTED", "PAID");
    static final Set<String> LIEN_RESOLUTIONS = Set.of("PAID", "NEGOTIATED", "WAIVED", "DEFERRED");
    static final Set<String> TERMINAL_LIEN_STATUSES = Set.of("RESOLVED", "WAIVED", "PAID");

    static final Set<String> TASK_PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    static final Set<String> TASK_STATUSES = Set.of("OPEN", "COMPLETE", "CANCELLED");
    static final Set<String> NOTE_TYPES = Set.of(
            "DIARY", "COVERAGE", "RESERVE", "LITIGATION", "SETTLEMENT", "GENERAL");

    static String normalize(Set<String> allowed, String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new ClaimValidationException(field + " is required");
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw new ClaimValidationException("Unknown " + field + ": " + raw);
        }
        return value;
    }

    static String optional(Set<String> allowed, String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return normalize(allowed, raw, field);
    }

    static String requiredText(String raw, String field, int maxLength) {
        if (raw == null || raw.isBlank()) {
            throw new ClaimValidationException(field + " is required");
        }
        String value = raw.trim();
        if (value.length() > maxLength) {
            throw new ClaimValidationException(field + " must be at most " + maxLength + " characters");
        }
        return value;
    }

    static String optionalText(String raw, int maxLength) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > maxLength) {
            throw new ClaimValidationException("Value must be at most " + maxLength + " characters");
        }
        return value;
    }

    static UUID requiredId(UUID value, String field) {
        if (value == null) {
            throw new ClaimValidationException(field + " is required");
        }
        return value;
    }

    static LocalDate requiredDate(LocalDate value, String field) {
        if (value == null) {
            throw new ClaimValidationException(field + " is required");
        }
        return value;
    }

    static BigDecimal amount(BigDecimal value, String field, boolean allowZero) {
        if (value == null) {
            throw new ClaimValidationException(field + " is required");
        }
        BigDecimal scaled = value.setScale(4, RoundingMode.HALF_UP);
        if (scaled.signum() < 0) {
            throw new ClaimValidationException(field + " must be zero or greater");
        }
        if (!allowZero && scaled.signum() == 0) {
            throw new ClaimValidationException(field + " must be greater than zero");
        }
        return scaled;
    }

    static String currency(String raw, String fallback) {
        String value = raw == null || raw.isBlank() ? fallback : raw.trim().toUpperCase(Locale.ROOT);
        if (value == null || !value.matches("[A-Z]{3}")) {
            throw new ClaimValidationException("currency must be a three-letter ISO currency code");
        }
        return value;
    }

    static List<String> diagnosisCodes(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > 20) {
            throw new ClaimValidationException("diagnosisCodes must contain at most 20 codes");
        }
        return raw.stream()
                .map(code -> code == null ? "" : code.trim().toUpperCase(Locale.ROOT))
                .peek(code -> {
                    if (!code.matches("[A-Z0-9.\\-]{1,16}")) {
                        throw new ClaimValidationException("Invalid diagnosis code: " + code);
                    }
                })
                .toList();
    }
}
