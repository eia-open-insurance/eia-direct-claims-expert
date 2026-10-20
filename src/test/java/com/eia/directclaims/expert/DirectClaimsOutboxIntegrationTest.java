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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that Direct Claims writes its canonical catalog events to the
 * transactional outbox in the same transaction as the aggregate change, and
 * that a rollback leaves neither the change nor the event behind.
 */
@SpringBootTest
@ActiveProfiles("test")
class DirectClaimsOutboxIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID CLAIMANT_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID REVIEWER_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final UUID APPROVER_ID = UUID.fromString("55555555-5555-7555-8555-555555555555");

    @Autowired
    private ClaimService claimService;
    @Autowired
    private CoverageService coverageService;
    @Autowired
    private ReserveService reserveService;
    @Autowired
    private SettlementService settlementService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM claim");
    }

    // ------------------------------------------------------------------ create
    @Test
    void createWritesRegisteredEventWithCanonicalPayload() {
        Claim claim = createClaim(new BigDecimal("125000.00"));

        Map<String, Object> event = onlyEvent(claim.id());
        assertThat(event.get("event_type")).isEqualTo("direct.claim.registered");
        assertThat(event.get("aggregate_type")).isEqualTo("Claim");
        assertThat(aggregateVersion(event)).isEqualTo(1L);
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(event.get("tenant_id")).isEqualTo(TENANT);
        assertThat(event.get("partition_key")).isEqualTo(claim.id().toString());

        Map<String, Object> payload = payload(event);
        assertThat(payload.get("claimId")).isEqualTo(claim.id().toString());
        assertThat(payload.get("claimNumber")).isEqualTo(claim.claimNumber());
        assertThat(payload.get("policyId")).isEqualTo(POLICY_ID.toString());
        assertThat(payload.get("claimantPartyId")).isEqualTo(CLAIMANT_ID.toString());
        assertThat(payload.get("status")).isEqualTo("REGISTERED");
        assertThat(payload.get("version")).isEqualTo(0);
        assertThat(payload.get("reserveAmount")).isEqualTo("125000.0000");
        assertThat(payload.get("currentReserveAmount")).isEqualTo("125000.0000");
        assertThat(payload.get("reserveCurrency")).isEqualTo("USD");
        assertThat(payload.get("claimType")).isEqualTo("PROPERTY_DAMAGE");
        assertThat(payload.get("lossDate")).isEqualTo("2026-03-14");
        assertThat(payload.get("reportDate")).isEqualTo("2026-03-18");

        Map<String, Object> headers = EventJson.toMap((String) event.get("headers"));
        assertThat(headers.get("book")).isEqualTo("DIRECT");
        assertThat(headers.get("vertical")).isEqualTo("direct");
        @SuppressWarnings("unchecked")
        Map<String, Object> subject = (Map<String, Object>) headers.get("subject");
        assertThat(subject.get("businessKey")).isEqualTo(claim.claimNumber());
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) headers.get("metadata");
        assertThat(metadata.get("schemaVersion")).isEqualTo("1.0.0");
    }

    @Test
    void createRollbackLeavesNeitherTheClaimNorTheEvent() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            createClaim(new BigDecimal("1000.00"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("claim")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    // ---------------------------------------------------------------- coverage
    @Test
    void coverageDecisionWritesCoverageAssessedEvent() {
        Claim claim = createClaim(new BigDecimal("0.00"));

        CoverageDecision decision = coverageService.decide(TENANT, claim.id(), coverageRequest("COVERED"));

        Map<String, Object> event = lastEvent(claim.id());
        assertThat(event.get("event_type")).isEqualTo("direct.claim.coverage-assessed");
        assertThat(aggregateVersion(event)).isEqualTo(2L);

        Map<String, Object> payload = payload(event);
        assertThat(payload.get("claimId")).isEqualTo(claim.id().toString());
        assertThat(payload.get("claimNumber")).isEqualTo(claim.claimNumber());
        assertThat(payload.get("policyId")).isEqualTo(POLICY_ID.toString());
        assertThat(payload.get("claimantPartyId")).isEqualTo(CLAIMANT_ID.toString());
        assertThat(payload.get("status")).isEqualTo("REGISTERED");
        assertThat(payload.get("version")).isEqualTo(1);
        assertThat(payload.get("currentReserveAmount")).isEqualTo("0.0000");
        assertThat(payload.get("coverageDecisionId")).isEqualTo(decision.id().toString());
        assertThat(payload.get("position")).isEqualTo("COVERED");
        assertThat(payload.get("coverageStatus")).isEqualTo("FINAL");
        assertThat(payload.get("basis")).isEqualTo("Policy year 2024 responds");
        assertThat(payload.get("allocationPct")).isEqualTo("0.250000");
        assertThat(payload.get("decisionDate")).isEqualTo("2026-03-18");
        assertThat(payload.get("reviewerPartyId")).isEqualTo(REVIEWER_ID.toString());
        assertThat(payload.get("coverageVersion")).isEqualTo(1);
    }

    @Test
    void coverageRollbackKeepsTheClaimVersionAndWritesNoEvent() {
        Claim claim = createClaim(new BigDecimal("0.00"));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            coverageService.decide(TENANT, claim.id(), coverageRequest("COVERED"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(claimVersion(claim.id())).isZero();
        assertThat(countWhere("claim_coverage_decision", claim.id())).isZero();
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(countEvents(claim.id(), "direct.claim.coverage-assessed")).isZero();
    }

    // ----------------------------------------------------------------- reserve
    @Test
    void reserveChangeWritesReserveChangedEventWithStringAmounts() {
        Claim claim = createClaim(new BigDecimal("0.00"));

        reserveService.change(TENANT, claim.id(), reserveRequest(
                "INDEMNITY", new BigDecimal("100000.00"), "INITIAL", "Manager", 0));

        Map<String, Object> event = lastEvent(claim.id());
        assertThat(event.get("event_type")).isEqualTo("direct.claim.reserve-changed");
        assertThat(aggregateVersion(event)).isEqualTo(2L);

        Map<String, Object> payload = payload(event);
        assertThat(payload.get("claimId")).isEqualTo(claim.id().toString());
        assertThat(payload.get("claimNumber")).isEqualTo(claim.claimNumber());
        assertThat(payload.get("status")).isEqualTo("REGISTERED");
        assertThat(payload.get("version")).isEqualTo(1);
        assertThat(payload.get("component")).isEqualTo("INDEMNITY");
        assertThat(payload.get("amount")).isEqualTo("100000.0000");
        assertThat(payload.get("currency")).isEqualTo("USD");
        assertThat(payload.get("reasonCode")).isEqualTo("INITIAL");
        assertThat(payload.get("sequence")).isEqualTo(1);
        assertThat(payload.get("previousAmount")).isEqualTo("0.0000");
        assertThat(payload.get("deltaAmount")).isEqualTo("100000.0000");
        assertThat(payload.get("totalCurrentReserveAmount")).isEqualTo("100000.0000");
        assertThat(payload.get("currentReserveAmount")).isEqualTo("100000.0000");
        assertThat(payload.get("reserveAmount")).isEqualTo("0.0000");
    }

    @Test
    void reserveRollbackKeepsTheClaimVersionAndWritesNoEvent() {
        Claim claim = createClaim(new BigDecimal("0.00"));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            reserveService.change(TENANT, claim.id(), reserveRequest(
                    "INDEMNITY", new BigDecimal("100000.00"), "INITIAL", "Manager", 0));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(claimVersion(claim.id())).isZero();
        assertThat(countWhere("claim_reserve_history", claim.id())).isZero();
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(countEvents(claim.id(), "direct.claim.reserve-changed")).isZero();
    }

    // -------------------------------------------------------------- settlement
    @Test
    void settlementApprovalWritesSettlementAuthorizedEvent() {
        Claim claim = createClaim(new BigDecimal("0.00"));
        coverageService.decide(TENANT, claim.id(), coverageRequest("COVERED"));
        Settlement proposed = settlementService.propose(TENANT, claim.id(), settlementRequest());

        Settlement authorized = settlementService.approve(TENANT, claim.id(), proposed.id(),
                new SettlementApprovalRequest("Claims Committee", APPROVER_ID, proposed.version()));

        Map<String, Object> event = lastEvent(claim.id());
        assertThat(event.get("event_type")).isEqualTo("direct.claim.settlement-authorized");
        assertThat(aggregateVersion(event)).isEqualTo(3L);

        Map<String, Object> payload = payload(event);
        assertThat(payload.get("claimId")).isEqualTo(claim.id().toString());
        assertThat(payload.get("claimNumber")).isEqualTo(claim.claimNumber());
        assertThat(payload.get("status")).isEqualTo("REGISTERED");
        assertThat(payload.get("version")).isEqualTo(2);
        assertThat(payload.get("settlementId")).isEqualTo(authorized.id().toString());
        assertThat(payload.get("settlementNumber")).isEqualTo(authorized.settlementNumber());
        assertThat(payload.get("settlementAmount")).isEqualTo("60000.0000");
        assertThat(payload.get("settlementCurrency")).isEqualTo("USD");
        assertThat(payload.get("settlementStatus")).isEqualTo("AUTHORIZED");
        assertThat(payload.get("settlementVersion")).isEqualTo(2);
        assertThat(payload.get("structure")).isEqualTo("LUMP_SUM");
        assertThat(payload.get("releaseScope")).isEqualTo("FULL");
        assertThat(payload.get("approvalAuthority")).isEqualTo("Claims Committee");
        assertThat(payload.get("authorizedBy")).isEqualTo(APPROVER_ID.toString());
        assertThat(payload.get("authorizedAt")).isNotNull();
    }

    @Test
    void settlementRollbackKeepsTheProposalAndWritesNoEvent() {
        Claim claim = createClaim(new BigDecimal("0.00"));
        coverageService.decide(TENANT, claim.id(), coverageRequest("COVERED"));
        Settlement proposed = settlementService.propose(TENANT, claim.id(), settlementRequest());

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            settlementService.approve(TENANT, claim.id(), proposed.id(),
                    new SettlementApprovalRequest("Claims Committee", APPROVER_ID, proposed.version()));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(claimVersion(claim.id())).isEqualTo(1);
        Map<String, Object> settlement = jdbc.queryForMap("""
                SELECT status, version FROM claim_settlement WHERE id = ?
                """, proposed.id());
        assertThat(settlement.get("status")).isEqualTo("PROPOSED");
        assertThat(((Number) settlement.get("version")).intValue()).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(2);
        assertThat(countEvents(claim.id(), "direct.claim.settlement-authorized")).isZero();
    }

    // ------------------------------------------------------------------- close
    @Test
    void closeWritesClosedEventAndPrecedingStatusUpdates() {
        Claim claim = createClaim(new BigDecimal("0.00"));
        coverageService.decide(TENANT, claim.id(), coverageRequest("COVERED"));
        Claim paid = driveToPaid(claim.id());
        assertThat(paid.status()).isEqualTo("PAID");

        Claim closed = claimService.close(TENANT, claim.id(), paid.version());

        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(eventTypes(claim.id())).containsExactly(
                "direct.claim.registered",
                "direct.claim.coverage-assessed",
                "direct.claim.updated",
                "direct.claim.updated",
                "direct.claim.updated",
                "direct.claim.updated",
                "direct.claim.updated",
                "direct.claim.updated",
                "direct.claim.closed");

        Map<String, Object> event = lastEvent(claim.id());
        assertThat(event.get("event_type")).isEqualTo("direct.claim.closed");
        assertThat(aggregateVersion(event)).isEqualTo(9L);

        Map<String, Object> payload = payload(event);
        assertThat(payload.get("claimId")).isEqualTo(claim.id().toString());
        assertThat(payload.get("claimNumber")).isEqualTo(claim.claimNumber());
        assertThat(payload.get("status")).isEqualTo("CLOSED");
        assertThat(payload.get("version")).isEqualTo(8);
        assertThat(payload.get("from")).isEqualTo("PAID");
        assertThat(payload.get("to")).isEqualTo("CLOSED");
        assertThat(payload.get("finalReserveAmount")).isEqualTo("0.0000");
        assertThat(payload.get("closedAt")).isNotNull();
    }

    @Test
    void closeRollbackKeepsThePaidStateAndWritesNoClosedEvent() {
        Claim claim = createClaim(new BigDecimal("0.00"));
        coverageService.decide(TENANT, claim.id(), coverageRequest("COVERED"));
        Claim paid = driveToPaid(claim.id());

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            claimService.close(TENANT, claim.id(), paid.version());
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(claimVersion(claim.id())).isEqualTo(paid.version());
        assertThat(jdbc.queryForObject(
                "SELECT status FROM claim WHERE id = ?", String.class, claim.id()))
                .isEqualTo("PAID");
        assertThat(countEvents(claim.id(), "direct.claim.closed")).isZero();
        assertThat(countEvents(claim.id(), "direct.claim.updated")).isEqualTo(6);
    }

    // ---------------------------------------------------------------- helpers
    private Claim createClaim(BigDecimal reserve) {
        return claimService.create(new CreateClaimRequest(
                TENANT, POLICY_ID, CLAIMANT_ID,
                LocalDate.of(2026, 3, 14), LocalDate.of(2026, 3, 18),
                "GL", "PROPERTY_DAMAGE", reserve, "USD",
                new BigDecimal("500000.00"), "USD", null));
    }

    private Claim driveToPaid(UUID claimId) {
        Claim claim = claimService.get(TENANT, claimId);
        for (String status : List.of(
                "OPEN", "RESERVED", "IN_SETTLEMENT", "SETTLED", "PAYMENT_PENDING", "PAID")) {
            claim = claimService.changeStatus(TENANT, claimId, status, claim.version());
        }
        return claim;
    }

    private static CoverageDecisionRequest coverageRequest(String position) {
        return new CoverageDecisionRequest(position, "Policy year 2024 responds", "Coverage A",
                "PRO_RATA_TIME", new BigDecimal("0.25"), LocalDate.of(2026, 3, 18), REVIEWER_ID);
    }

    private static ReserveChangeRequest reserveRequest(String component, BigDecimal amount,
                                                       String reason, String authority,
                                                       int expectedVersion) {
        return new ReserveChangeRequest(component, amount, "USD", LocalDate.of(2026, 3, 18),
                reason, "Initial case reserve", authority, expectedVersion);
    }

    private static SettlementRequest settlementRequest() {
        return new SettlementRequest(new BigDecimal("60000.00"), "USD", "LUMP_SUM", "FULL",
                "Wire to claimant trust account");
    }

    private Map<String, Object> onlyEvent(UUID claimId) {
        List<Map<String, Object>> events = events(claimId);
        assertThat(events).hasSize(1);
        return events.get(0);
    }

    private Map<String, Object> lastEvent(UUID claimId) {
        List<Map<String, Object>> events = events(claimId);
        assertThat(events).isNotEmpty();
        return events.get(events.size() - 1);
    }

    private List<Map<String, Object>> events(UUID claimId) {
        return jdbc.queryForList("""
                SELECT event_type, aggregate_type, aggregate_version, status, tenant_id,
                       payload, headers, partition_key
                  FROM outbox_event
                 WHERE aggregate_id = ?
                 ORDER BY event_sequence
                """, claimId);
    }

    private List<String> eventTypes(UUID claimId) {
        return events(claimId).stream()
                .map(event -> (String) event.get("event_type"))
                .toList();
    }

    private static long aggregateVersion(Map<String, Object> event) {
        return ((Number) event.get("aggregate_version")).longValue();
    }

    private static Map<String, Object> payload(Map<String, Object> event) {
        return EventJson.toMap((String) event.get("payload"));
    }

    private int claimVersion(UUID claimId) {
        return jdbc.queryForObject(
                "SELECT version FROM claim WHERE id = ?", Integer.class, claimId);
    }

    private int countEvents(UUID claimId, String eventType) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = ?
                """, Integer.class, claimId, eventType);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int countWhere(String table, UUID claimId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE claim_id = ?", Integer.class, claimId);
    }
}
