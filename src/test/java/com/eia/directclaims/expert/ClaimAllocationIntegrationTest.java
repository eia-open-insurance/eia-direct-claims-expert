package com.eia.directclaims.expert;

import com.eia.platform.libs.events.EventJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end claim allocation persistence, ordering, summary, validation,
 * tenant isolation, outbox payload, and transactional rollback against H2.
 */
@SpringBootTest
@ActiveProfiles("test")
class ClaimAllocationIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID POLICY_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID OTHER_POLICY_ID = UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final UUID CLAIMANT_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID LIMIT_ID = UUID.fromString("66666666-6666-7666-8666-666666666666");

    @Autowired
    private ClaimService claimService;
    @Autowired
    private ClaimAllocationService allocationService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM claim_allocation");
        jdbc.update("DELETE FROM claim_note");
        jdbc.update("DELETE FROM claim_task");
        jdbc.update("DELETE FROM claim");
    }

    @Test
    void createsAndListsAllocationsOldestFirstWithSummaryTotals() {
        Claim claim = createClaim();

        ClaimAllocation first = allocationService.create(TENANT, claim.id(),
                request(POLICY_ID, 2023, "PRIMARY", "0.25", "100000.00"));
        sleepMillis(5);
        ClaimAllocation second = allocationService.create(TENANT, claim.id(),
                request(POLICY_ID, 2024, "EXCESS", "0.50", "150000.00"));

        ClaimAllocationSummary summary = allocationService.list(TENANT, claim.id());

        assertThat(summary.items()).extracting(ClaimAllocation::id)
                .containsExactly(first.id(), second.id());
        assertThat(summary.items()).extracting(ClaimAllocation::layer)
                .containsExactly("PRIMARY", "EXCESS");
        assertThat(summary.totalPct().toPlainString()).isEqualTo("0.750000");
        assertThat(summary.totalAmount().toPlainString()).isEqualTo("250000.0000");
        assertThat(claimVersion(claim.id())).isEqualTo(2);
        assertThat(countEvents(claim.id(), "direct.claim.allocation-created")).isEqualTo(2);

        List<Map<String, Object>> events = events(claim.id());
        assertThat(aggregateVersion(events.get(events.size() - 1))).isEqualTo(3L);
    }

    @Test
    void allocatedAmountMayExceedTheCurrentReserve() {
        Claim claim = createClaim();

        ClaimAllocation allocation = allocationService.create(TENANT, claim.id(),
                request(POLICY_ID, 2024, "EXCESS", "0.50", "999999999.00"));

        assertThat(allocation.allocatedAmount()).isEqualByComparingTo("999999999.0000");
        assertThat(count("claim_allocation")).isEqualTo(1);
    }

    @Test
    void rejectsAPercentageAboveOne() {
        Claim claim = createClaim();

        assertThatThrownBy(() -> allocationService.create(TENANT, claim.id(),
                request(POLICY_ID, 2024, "PRIMARY", "1.000001", "1000.00")))
                .isInstanceOf(ClaimValidationException.class)
                .hasMessageContaining("allocationPct");

        assertThat(count("claim_allocation")).isZero();
        assertThat(claimVersion(claim.id())).isZero();
        assertThat(countEvents(claim.id(), "direct.claim.allocation-created")).isZero();
    }

    @Test
    void rejectsATotalPercentageAboveOne() {
        Claim claim = createClaim();
        allocationService.create(TENANT, claim.id(),
                request(POLICY_ID, 2023, "PRIMARY", "0.75", "100000.00"));

        assertThatThrownBy(() -> allocationService.create(TENANT, claim.id(),
                request(POLICY_ID, 2024, "EXCESS", "0.50", "50000.00")))
                .isInstanceOf(AllocationExceedsHundredException.class)
                .hasMessageContaining("exceeds 1");

        assertThat(count("claim_allocation")).isEqualTo(1);
        assertThat(claimVersion(claim.id())).isEqualTo(1);
        assertThat(countEvents(claim.id(), "direct.claim.allocation-created")).isEqualTo(1);
    }

    @Test
    void rejectsAPolicyThatIsNotTheClaimsPolicy() {
        Claim claim = createClaim();

        assertThatThrownBy(() -> allocationService.create(TENANT, claim.id(),
                request(OTHER_POLICY_ID, 2024, "PRIMARY", "0.50", "1000.00")))
                .isInstanceOf(PolicyMismatchException.class)
                .hasMessageContaining("does not match the claim policy");

        assertThat(count("claim_allocation")).isZero();
        assertThat(countEvents(claim.id(), "direct.claim.allocation-created")).isZero();
    }

    @Test
    void rejectsAPolicyYearOutsideTheSupportedRange() {
        Claim claim = createClaim();

        assertThatThrownBy(() -> allocationService.create(TENANT, claim.id(),
                request(POLICY_ID, 1899, "PRIMARY", "0.25", "1000.00")))
                .isInstanceOf(ClaimValidationException.class)
                .hasMessageContaining("1900");
        assertThatThrownBy(() -> allocationService.create(TENANT, claim.id(),
                request(POLICY_ID, 2201, "PRIMARY", "0.25", "1000.00")))
                .isInstanceOf(ClaimValidationException.class)
                .hasMessageContaining("2200");

        assertThat(count("claim_allocation")).isZero();
    }

    @Test
    void acceptsThePolicyYearBounds() {
        Claim claim = createClaim();

        allocationService.create(TENANT, claim.id(),
                request(POLICY_ID, 1900, "PRIMARY", "0.25", "1000.00"));
        allocationService.create(TENANT, claim.id(),
                request(POLICY_ID, 2200, "PRIMARY", "0.25", "1000.00"));

        assertThat(allocationService.list(TENANT, claim.id()).items())
                .extracting(ClaimAllocation::policyYear)
                .containsExactlyInAnyOrder(1900, 2200);
    }

    @Test
    void allocationsAreHiddenFromAnotherTenant() {
        Claim claim = createClaim();
        allocationService.create(TENANT, claim.id(),
                request(POLICY_ID, 2024, "PRIMARY", "0.25", "1000.00"));

        assertThatThrownBy(() -> allocationService.list(OTHER_TENANT, claim.id()))
                .isInstanceOf(ClaimNotFoundException.class);
        assertThatThrownBy(() -> allocationService.create(OTHER_TENANT, claim.id(),
                request(POLICY_ID, 2024, "EXCESS", "0.25", "1000.00")))
                .isInstanceOf(ClaimNotFoundException.class);

        assertThat(count("claim_allocation")).isEqualTo(1);
        assertThat(claimVersion(claim.id())).isEqualTo(1);
    }

    @Test
    void allocationCreateWritesTheEventInTheSameTransaction() {
        Claim claim = createClaim();

        ClaimAllocation allocation = allocationService.create(TENANT, claim.id(),
                request(POLICY_ID, 2024, "PRIMARY", "0.25", "100000.00"));

        Map<String, Object> event = lastEvent(claim.id());
        assertThat(event.get("event_type")).isEqualTo("direct.claim.allocation-created");
        assertThat(event.get("aggregate_type")).isEqualTo("Claim");
        assertThat(event.get("aggregate_id")).isEqualTo(claim.id());
        assertThat(aggregateVersion(event)).isEqualTo(2L);

        Map<String, Object> payload = payload(event);
        assertThat(payload.get("claimId")).isEqualTo(claim.id().toString());
        assertThat(payload.get("claimNumber")).isEqualTo(claim.claimNumber());
        assertThat(payload.get("policyId")).isEqualTo(POLICY_ID.toString());
        assertThat(payload.get("policyYear")).isEqualTo(2024);
        assertThat(payload.get("layer")).isEqualTo("PRIMARY");
        assertThat(payload.get("allocationPct")).isEqualTo("0.250000");
        assertThat(payload.get("allocatedAmount")).isEqualTo("100000.0000");
        assertThat(payload.get("totalPct")).isEqualTo("0.250000");
        assertThat(payload.get("totalAmount")).isEqualTo("100000.0000");
        assertThat(allocation.id()).isNotNull();
    }

    @Test
    void rollbackLeavesNoAllocationEventOrVersionBump() {
        Claim claim = createClaim();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            allocationService.create(TENANT, claim.id(),
                    request(POLICY_ID, 2024, "PRIMARY", "0.25", "100000.00"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("claim_allocation")).isZero();
        assertThat(claimVersion(claim.id())).isZero();
        assertThat(countEvents(claim.id(), "direct.claim.allocation-created")).isZero();
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test
    void persistsTheOptionalLimitAndDefaultsTheErosionState() {
        Claim claim = createClaim();

        ClaimAllocation withLimit = allocationService.create(TENANT, claim.id(),
                request(POLICY_ID, 2024, "PRIMARY", "0.25", "1000.00", LIMIT_ID));
        ClaimAllocation withoutLimit = allocationService.create(TENANT, claim.id(),
                request(POLICY_ID, 2024, "EXCESS", "0.25", "1000.00"));

        assertThat(withLimit.limitId()).isEqualTo(LIMIT_ID);
        assertThat(withLimit.erosionStatus()).isEqualTo("NOT_POSTED");
        assertThat(withLimit.erosionMessage()).isNull();
        assertThat(withLimit.erosionPostedAt()).isNull();
        assertThat(withoutLimit.limitId()).isNull();
        assertThat(withoutLimit.erosionStatus()).isEqualTo("NOT_POSTED");

        ClaimAllocationSummary summary = allocationService.list(TENANT, claim.id());
        assertThat(summary.items()).extracting(ClaimAllocation::limitId)
                .containsExactlyInAnyOrder(LIMIT_ID, null);
        assertThat(summary.items()).extracting(ClaimAllocation::erosionStatus)
                .containsOnly("NOT_POSTED");
        assertThat(jdbc.queryForObject(
                "SELECT erosion_status FROM claim_allocation WHERE id = ?",
                String.class, withoutLimit.id())).isEqualTo("NOT_POSTED");
    }

    @Test
    void postsAnErosionStateAndStampsThePostingInstant() {
        Claim claim = createClaim();
        ClaimAllocation allocation = allocationService.create(TENANT, claim.id(),
                request(POLICY_ID, 2024, "PRIMARY", "0.25", "1000.00", LIMIT_ID));
        Instant before = Instant.now().minusSeconds(5);

        ClaimAllocation posted = allocationService.updateErosion(TENANT, claim.id(), allocation.id(),
                new AllocationErosionRequest("POSTED", "limit-erosion-2026-0001"));

        assertThat(posted.erosionStatus()).isEqualTo("POSTED");
        assertThat(posted.erosionMessage()).isEqualTo("limit-erosion-2026-0001");
        assertThat(posted.erosionPostedAt()).isAfter(before).isBefore(Instant.now().plusSeconds(5));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT erosion_status, erosion_message, erosion_posted_at
                  FROM claim_allocation WHERE id = ?
                """, allocation.id());
        assertThat(row.get("erosion_status")).isEqualTo("POSTED");
        assertThat(row.get("erosion_message")).isEqualTo("limit-erosion-2026-0001");
        assertThat(row.get("erosion_posted_at")).isNotNull();

        // Recording the posting is a child-state change: it neither bumps the
        // claim version nor writes another outbox event.
        assertThat(claimVersion(claim.id())).isEqualTo(1);
        assertThat(countEvents(claim.id(), "direct.claim.allocation-created")).isEqualTo(1);
    }

    @Test
    void recordsAFailedErosionStateWithoutAPostingInstant() {
        Claim claim = createClaim();
        ClaimAllocation allocation = allocationService.create(TENANT, claim.id(),
                request(POLICY_ID, 2024, "PRIMARY", "0.25", "1000.00", LIMIT_ID));

        ClaimAllocation failed = allocationService.updateErosion(TENANT, claim.id(), allocation.id(),
                new AllocationErosionRequest("FAILED", "limit service unavailable"));

        assertThat(failed.erosionStatus()).isEqualTo("FAILED");
        assertThat(failed.erosionMessage()).isEqualTo("limit service unavailable");
        assertThat(failed.erosionPostedAt()).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM claim_allocation
                 WHERE id = ? AND erosion_posted_at IS NULL
                """, Integer.class, allocation.id())).isEqualTo(1);
    }

    @Test
    void aFailedErosionPostingCanBeRetriedAsPosted() {
        Claim claim = createClaim();
        ClaimAllocation allocation = allocationService.create(TENANT, claim.id(),
                request(POLICY_ID, 2024, "PRIMARY", "0.25", "1000.00", LIMIT_ID));

        allocationService.updateErosion(TENANT, claim.id(), allocation.id(),
                new AllocationErosionRequest("FAILED", "timeout"));
        ClaimAllocation posted = allocationService.updateErosion(TENANT, claim.id(), allocation.id(),
                new AllocationErosionRequest("POSTED", "retry accepted"));

        assertThat(posted.erosionStatus()).isEqualTo("POSTED");
        assertThat(posted.erosionMessage()).isEqualTo("retry accepted");
        assertThat(posted.erosionPostedAt()).isNotNull();
    }

    @Test
    void repeatingThePostedErosionStatusIsAnIdempotentNoOp() {
        Claim claim = createClaim();
        ClaimAllocation allocation = allocationService.create(TENANT, claim.id(),
                request(POLICY_ID, 2024, "PRIMARY", "0.25", "1000.00", LIMIT_ID));

        ClaimAllocation first = allocationService.updateErosion(TENANT, claim.id(), allocation.id(),
                new AllocationErosionRequest("POSTED", "first posting"));
        sleepMillis(5);
        ClaimAllocation second = allocationService.updateErosion(TENANT, claim.id(), allocation.id(),
                new AllocationErosionRequest("POSTED", "second posting"));

        assertThat(second.erosionStatus()).isEqualTo("POSTED");
        assertThat(second.erosionMessage()).isEqualTo("first posting");
        assertThat(second.erosionPostedAt()).isEqualTo(first.erosionPostedAt());
    }

    @Test
    void rejectsChangingAnAllocationThatIsAlreadyPosted() {
        Claim claim = createClaim();
        ClaimAllocation allocation = allocationService.create(TENANT, claim.id(),
                request(POLICY_ID, 2024, "PRIMARY", "0.25", "1000.00", LIMIT_ID));
        ClaimAllocation posted = allocationService.updateErosion(TENANT, claim.id(), allocation.id(),
                new AllocationErosionRequest("POSTED", "first posting"));

        assertThatThrownBy(() -> allocationService.updateErosion(TENANT, claim.id(), allocation.id(),
                new AllocationErosionRequest("FAILED", "too late")))
                .isInstanceOf(ClaimConflictException.class)
                .hasMessageContaining("already POSTED");

        ClaimAllocation stored = allocationService.list(TENANT, claim.id()).items().get(0);
        assertThat(stored.erosionStatus()).isEqualTo("POSTED");
        assertThat(stored.erosionMessage()).isEqualTo("first posting");
        assertThat(stored.erosionPostedAt()).isEqualTo(posted.erosionPostedAt());
    }

    @Test
    void erosionUpdatesAreHiddenFromAnotherTenant() {
        Claim claim = createClaim();
        ClaimAllocation allocation = allocationService.create(TENANT, claim.id(),
                request(POLICY_ID, 2024, "PRIMARY", "0.25", "1000.00", LIMIT_ID));

        assertThatThrownBy(() -> allocationService.updateErosion(OTHER_TENANT, claim.id(),
                allocation.id(), new AllocationErosionRequest("POSTED", null)))
                .isInstanceOf(ClaimNotFoundException.class);

        ClaimAllocation stored = allocationService.list(TENANT, claim.id()).items().get(0);
        assertThat(stored.erosionStatus()).isEqualTo("NOT_POSTED");
        assertThat(stored.erosionMessage()).isNull();
        assertThat(stored.erosionPostedAt()).isNull();
    }

    @Test
    void erosionUpdatesAreHiddenFromAnotherClaimInTheSameTenant() {
        Claim claim = createClaim();
        Claim otherClaim = createClaim();
        ClaimAllocation allocation = allocationService.create(TENANT, claim.id(),
                request(POLICY_ID, 2024, "PRIMARY", "0.25", "1000.00", LIMIT_ID));

        assertThatThrownBy(() -> allocationService.updateErosion(TENANT, otherClaim.id(),
                allocation.id(), new AllocationErosionRequest("POSTED", null)))
                .isInstanceOf(RecordNotFoundException.class);

        ClaimAllocation stored = allocationService.list(TENANT, claim.id()).items().get(0);
        assertThat(stored.erosionStatus()).isEqualTo("NOT_POSTED");
        assertThat(stored.erosionMessage()).isNull();
        assertThat(stored.erosionPostedAt()).isNull();
    }

    @Test
    void erosionUpdateOfAnUnknownAllocationIs404() {
        Claim claim = createClaim();

        assertThatThrownBy(() -> allocationService.updateErosion(TENANT, claim.id(),
                UUID.randomUUID(), new AllocationErosionRequest("POSTED", null)))
                .isInstanceOf(RecordNotFoundException.class);
    }

    // ------------------------------------------------------------------ helpers
    private Claim createClaim() {
        return claimService.create(new CreateClaimRequest(
                TENANT, POLICY_ID, CLAIMANT_ID,
                LocalDate.of(2026, 3, 14), LocalDate.of(2026, 3, 18),
                "GL", "PROPERTY_DAMAGE", new BigDecimal("125000.00"), "USD",
                new BigDecimal("500000.00"), "USD", null));
    }

    private static CreateClaimAllocationRequest request(UUID policyId, Integer policyYear,
                                                        String layer, String pct, String amount) {
        return request(policyId, policyYear, layer, pct, amount, null);
    }

    private static CreateClaimAllocationRequest request(UUID policyId, Integer policyYear,
                                                        String layer, String pct, String amount,
                                                        UUID limitId) {
        return new CreateClaimAllocationRequest(policyId, policyYear, layer,
                new BigDecimal(pct), new BigDecimal(amount), limitId);
    }

    private int claimVersion(UUID claimId) {
        return jdbc.queryForObject("SELECT version FROM claim WHERE id = ?", Integer.class, claimId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private List<Map<String, Object>> events(UUID claimId) {
        return jdbc.queryForList("""
                SELECT event_type, aggregate_type, aggregate_id, aggregate_version, payload
                  FROM outbox_event
                 WHERE aggregate_id = ?
                 ORDER BY event_sequence
                """, claimId);
    }

    private Map<String, Object> lastEvent(UUID claimId) {
        List<Map<String, Object>> events = events(claimId);
        assertThat(events).isNotEmpty();
        return events.get(events.size() - 1);
    }

    private int countEvents(UUID claimId, String eventType) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = ?
                """, Integer.class, claimId, eventType);
    }

    private static long aggregateVersion(Map<String, Object> event) {
        return ((Number) event.get("aggregate_version")).longValue();
    }

    private static Map<String, Object> payload(Map<String, Object> event) {
        return EventJson.toMap((String) event.get("payload"));
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while separating created_at instants", ex);
        }
    }
}
