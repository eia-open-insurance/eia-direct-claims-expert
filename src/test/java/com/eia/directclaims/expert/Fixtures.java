package com.eia.directclaims.expert;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Shared values for the second-slice tests. */
final class Fixtures {
    static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    static final UUID CLAIM_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d21");
    static final UUID POLICY_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    static final UUID CLAIMANT_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    static final UUID OTHER_PARTY_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    static final UUID INJURY_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d31");
    static final UUID EXPOSURE_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d32");
    static final UUID RESERVE_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d33");
    static final UUID COVERAGE_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d34");
    static final UUID LITIGATION_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d35");
    static final UUID SETTLEMENT_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d36");
    static final UUID PAYMENT_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d3c");
    static final UUID RECOVERY_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d37");
    static final UUID LIEN_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d38");
    static final UUID TASK_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d39");
    static final UUID NOTE_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d3a");
    static final UUID ALLOCATION_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d3b");
    static final UUID LIMIT_ID = UUID.fromString("66666666-6666-7666-8666-666666666666");
    static final UUID OTHER_POLICY_ID = UUID.fromString("55555555-5555-7555-8555-555555555555");
    static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    static final LocalDate LOSS_DATE = LocalDate.of(2026, 3, 14);
    static final LocalDate REPORT_DATE = LocalDate.of(2026, 3, 18);
    static final LocalDate TASK_DUE_DATE = LocalDate.of(2026, 4, 15);
    static final Instant NOW = Instant.parse("2026-03-18T09:00:00Z");

    private Fixtures() {
    }

    static Claim claim(String status, int version) {
        return claim("BODILY_INJURY", status, version, new BigDecimal("500000.0000"));
    }

    static Claim claim(String claimType, String status, int version, BigDecimal authority) {
        return new Claim(CLAIM_ID, TENANT, "DIRECT", "CLM-2026-0000001", POLICY_ID, CLAIMANT_ID,
                claimType, status, LOSS_DATE, REPORT_DATE, "GL",
                new BigDecimal("125000.0000"), "USD", authority, authority == null ? null : "USD",
                false, null, null, null, null, new BigDecimal("125000.0000"), version,
                Instant.parse("2026-03-18T09:00:00Z"), Instant.parse("2026-03-18T09:00:00Z"));
    }

    static Claim assignedClaim(String status, int version) {
        return new Claim(CLAIM_ID, TENANT, "DIRECT", "CLM-2026-0000001", POLICY_ID, CLAIMANT_ID,
                "BODILY_INJURY", status, LOSS_DATE, REPORT_DATE, "GL",
                new BigDecimal("125000.0000"), "USD", new BigDecimal("500000.0000"), "USD",
                false, "adjuster@example.com", "CASUALTY", NOW, "supervisor@example.com",
                new BigDecimal("125000.0000"), version,
                Instant.parse("2026-03-18T09:00:00Z"), Instant.parse("2026-03-18T09:00:00Z"));
    }

    static ClaimInjury injury() {
        return new ClaimInjury(INJURY_ID, TENANT, CLAIM_ID, CLAIMANT_ID, "MESOTHELIOMA", "LUNG",
                List.of("C45.0"), "Pleural mesothelioma", "SEVERE", LocalDate.of(2026, 1, 10), 1,
                NOW, NOW);
    }

    static ClaimExposure exposure() {
        return new ClaimExposure(EXPOSURE_ID, TENANT, CLAIM_ID, CLAIMANT_ID, "SUBSTANCE",
                null, "Chrysotile asbestos", "Akron plant", null, "Machinist", "US-OH",
                LocalDate.of(2015, 1, 1), LocalDate.of(2020, 12, 31), "HIGH", 1, NOW, NOW);
    }

    static ReserveHistoryEntry reserve() {
        return new ReserveHistoryEntry(RESERVE_ID, TENANT, CLAIM_ID, "INDEMNITY",
                new BigDecimal("100000.0000"), "USD", LocalDate.of(2026, 3, 18), "INITIAL",
                "Initial case reserve", "Manager", 1, true, 1, NOW);
    }

    static CoverageDecision coverage(String position) {
        return new CoverageDecision(COVERAGE_ID, TENANT, CLAIM_ID, position, "Policy year 2024 responds",
                "Coverage A", "PRO_RATA_TIME", new BigDecimal("0.250000"), LocalDate.of(2026, 3, 18),
                OTHER_PARTY_ID, "FINAL", 1, NOW, NOW);
    }

    static LitigationMatter litigation() {
        return new LitigationMatter(LITIGATION_ID, TENANT, CLAIM_ID, "DEFENSE", "US-OH", OTHER_PARTY_ID,
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 9, 1), "ACTIVE", "CV-2026-001", 1, NOW, NOW);
    }

    static Settlement settlement(String status) {
        return settlement(status, null, null, 1);
    }

    static Settlement settlement(String status, UUID paymentId, Instant paymentLinkedAt, int version) {
        return new Settlement(SETTLEMENT_ID, TENANT, CLAIM_ID, "SET-2026-0000001",
                new BigDecimal("60000.0000"), "USD", "LUMP_SUM", "FULL", "Claims Committee",
                "Wire to claimant trust account", OTHER_PARTY_ID, status, OTHER_PARTY_ID, NOW, NOW,
                paymentId, paymentLinkedAt, version, NOW, NOW);
    }

    static Recovery recovery(String status) {
        return new Recovery(RECOVERY_ID, TENANT, CLAIM_ID, "SUBROGATION",
                new BigDecimal("20000.0000"), "USD", "Co-defendant Acme", status,
                LocalDate.of(2026, 5, 1), 1, NOW, NOW);
    }

    static Lien lien(String status) {
        boolean terminal = status.equals("RESOLVED") || status.equals("WAIVED") || status.equals("PAID");
        return new Lien(LIEN_ID, TENANT, CLAIM_ID, "MEDICARE", "Centers for Medicare & Medicaid Services",
                new BigDecimal("15000.0000"), "USD", status, terminal ? "NEGOTIATED" : null,
                terminal ? NOW : null, 1, NOW, NOW);
    }

    static ClaimTask task(String status, long version) {
        return task(status, version, TASK_DUE_DATE);
    }

    static ClaimTask task(String status, long version, LocalDate dueDate) {
        return new ClaimTask(TASK_ID, TENANT, CLAIM_ID, "TSK-2026-0000001",
                "Review medical records", "Order records and index the file", dueDate, "HIGH",
                status, "adjuster@example.com", version, NOW, NOW,
                "COMPLETE".equals(status) ? NOW : null);
    }

    static ClaimNote note(String noteType, String body) {
        return new ClaimNote(NOTE_ID, TENANT, CLAIM_ID, "NTE-2026-0000001", noteType, body,
                "adjuster@example.com", NOW);
    }

    static ClaimAllocation allocation(int policyYear, String layer, String pct, String amount) {
        return new ClaimAllocation(ALLOCATION_ID, TENANT, CLAIM_ID, POLICY_ID, null, policyYear, layer,
                new BigDecimal(pct), new BigDecimal(amount), "NOT_POSTED", null, null, NOW);
    }

    static ClaimAllocation allocation(UUID limitId, int policyYear, String layer, String pct,
                                      String amount) {
        return new ClaimAllocation(ALLOCATION_ID, TENANT, CLAIM_ID, POLICY_ID, limitId, policyYear,
                layer, new BigDecimal(pct), new BigDecimal(amount), "NOT_POSTED", null, null, NOW);
    }

    static ClaimAllocation postedAllocation(String message) {
        return new ClaimAllocation(ALLOCATION_ID, TENANT, CLAIM_ID, POLICY_ID, LIMIT_ID, 2024,
                "PRIMARY", new BigDecimal("0.250000"), new BigDecimal("100000.0000"), "POSTED",
                message, NOW, NOW);
    }

    static ClaimAllocation failedAllocation(String message) {
        return new ClaimAllocation(ALLOCATION_ID, TENANT, CLAIM_ID, POLICY_ID, LIMIT_ID, 2024,
                "PRIMARY", new BigDecimal("0.250000"), new BigDecimal("100000.0000"), "FAILED",
                message, null, NOW);
    }
}
