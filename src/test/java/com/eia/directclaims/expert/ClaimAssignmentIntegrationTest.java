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
 * End-to-end claim assignment: persistence, optimistic versioning, no-op
 * semantics, tenant isolation, outbox event payload, list projection, and
 * transactional rollback against the H2 test schema.
 */
@SpringBootTest
@ActiveProfiles("test")
class ClaimAssignmentIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID CLAIMANT_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");

    @Autowired
    private ClaimService claimService;
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

    @Test
    void assignPersistsFieldsBumpsVersionAndWritesTheOutboxEvent() {
        Claim claim = createClaim();

        Claim assigned = claimService.assign(TENANT, claim.id(),
                request("adjuster@example.com", "CASUALTY", claim.version()),
                "supervisor@example.com");

        assertThat(assigned.assignedAdjuster()).isEqualTo("adjuster@example.com");
        assertThat(assigned.assignedTeam()).isEqualTo("CASUALTY");
        assertThat(assigned.assignedAt()).isNotNull();
        assertThat(assigned.assignedBy()).isEqualTo("supervisor@example.com");
        assertThat(assigned.version()).isEqualTo(claim.version() + 1);

        Claim listed = claimService.list(TENANT, null).get(0);
        assertThat(listed.assignedAdjuster()).isEqualTo("adjuster@example.com");
        assertThat(listed.assignedTeam()).isEqualTo("CASUALTY");
        assertThat(listed.assignedAt()).isEqualTo(assigned.assignedAt());

        Map<String, Object> event = lastEvent(claim.id());
        assertThat(event.get("event_type")).isEqualTo(ClaimEvents.ASSIGNED);
        assertThat(event.get("aggregate_type")).isEqualTo("Claim");
        assertThat(event.get("partition_key")).isEqualTo(claim.id().toString());
        assertThat(aggregateVersion(event)).isEqualTo(2L);

        Map<String, Object> payload = payload(event);
        assertThat(payload.get("claimId")).isEqualTo(claim.id().toString());
        assertThat(payload.get("claimNumber")).isEqualTo(claim.claimNumber());
        assertThat(payload.get("assignedAdjuster")).isEqualTo("adjuster@example.com");
        assertThat(payload.get("assignedTeam")).isEqualTo("CASUALTY");
        assertThat(payload.get("assignedAt")).isNotNull();
        assertThat(payload.get("assignedBy")).isEqualTo("supervisor@example.com");
        assertThat(payload.get("version")).isEqualTo(1);
    }

    @Test
    void reassignReplacesTheAssignmentAndDefaultsAssignedByToTheAdjuster() {
        Claim claim = createClaim();
        Claim first = claimService.assign(TENANT, claim.id(),
                request("adjuster@example.com", "CASUALTY", claim.version()),
                "supervisor@example.com");

        Claim second = claimService.assign(TENANT, claim.id(),
                request("senior@example.com", "LITIGATION", first.version()), null);

        assertThat(second.assignedAdjuster()).isEqualTo("senior@example.com");
        assertThat(second.assignedTeam()).isEqualTo("LITIGATION");
        assertThat(second.assignedBy()).isEqualTo("senior@example.com");
        assertThat(second.version()).isEqualTo(first.version() + 1);
        assertThat(countEvents(claim.id(), ClaimEvents.ASSIGNED)).isEqualTo(2);

        Map<String, Object> payload = payload(lastEvent(claim.id()));
        assertThat(payload.get("assignedAdjuster")).isEqualTo("senior@example.com");
        assertThat(payload.get("assignedTeam")).isEqualTo("LITIGATION");
        assertThat(payload.get("assignedBy")).isEqualTo("senior@example.com");
        assertThat(payload.get("version")).isEqualTo(2);
        assertThat(aggregateVersion(lastEvent(claim.id()))).isEqualTo(3L);
    }

    @Test
    void staleVersionIs409AndLeavesTheAssignmentAndEventsUnchanged() {
        Claim claim = createClaim();
        Claim assigned = claimService.assign(TENANT, claim.id(),
                request("adjuster@example.com", "CASUALTY", claim.version()),
                "supervisor@example.com");

        assertThatThrownBy(() -> claimService.assign(TENANT, claim.id(),
                request("intruder@example.com", "LITIGATION", assigned.version() - 1),
                "supervisor@example.com"))
                .isInstanceOf(ClaimConflictException.class)
                .hasMessageContaining("does not match current version");

        Claim stored = claimService.get(TENANT, claim.id());
        assertThat(stored.assignedAdjuster()).isEqualTo("adjuster@example.com");
        assertThat(stored.assignedTeam()).isEqualTo("CASUALTY");
        assertThat(stored.version()).isEqualTo(assigned.version());
        assertThat(countEvents(claim.id(), ClaimEvents.ASSIGNED)).isEqualTo(1);
    }

    @Test
    void repeatingTheSameAssignmentAtTheCurrentVersionIsANoOp() {
        Claim claim = createClaim();
        Claim assigned = claimService.assign(TENANT, claim.id(),
                request("adjuster@example.com", "CASUALTY", claim.version()),
                "supervisor@example.com");

        Claim noOp = claimService.assign(TENANT, claim.id(),
                request("adjuster@example.com", "CASUALTY", assigned.version()),
                "another-actor@example.com");

        assertThat(noOp.version()).isEqualTo(assigned.version());
        assertThat(noOp.assignedAt()).isEqualTo(assigned.assignedAt());
        assertThat(noOp.assignedBy()).isEqualTo("supervisor@example.com");
        assertThat(countEvents(claim.id(), ClaimEvents.ASSIGNED)).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(2);
    }

    @Test
    void invalidAssignmentRequestsAreRejectedWithoutWriting() {
        Claim claim = createClaim();

        assertThatThrownBy(() -> claimService.assign(TENANT, claim.id(),
                request(" ", "CASUALTY", claim.version()), null))
                .isInstanceOf(ClaimValidationException.class)
                .hasMessageContaining("assignedAdjuster is required");
        assertThatThrownBy(() -> claimService.assign(TENANT, claim.id(),
                request("a".repeat(129), "CASUALTY", claim.version()), null))
                .isInstanceOf(ClaimValidationException.class)
                .hasMessageContaining("at most 128");
        assertThatThrownBy(() -> claimService.assign(TENANT, claim.id(),
                request("adjuster@example.com", "t".repeat(65), claim.version()), null))
                .isInstanceOf(ClaimValidationException.class)
                .hasMessageContaining("at most 64");
        assertThatThrownBy(() -> claimService.assign(TENANT, claim.id(),
                request("adjuster@example.com", null, null), null))
                .isInstanceOf(ClaimValidationException.class)
                .hasMessage("expectedVersion is required");

        Claim stored = claimService.get(TENANT, claim.id());
        assertThat(stored.assignedAdjuster()).isNull();
        assertThat(stored.assignedTeam()).isNull();
        assertThat(stored.assignedAt()).isNull();
        assertThat(stored.version()).isZero();
        assertThat(countEvents(claim.id(), ClaimEvents.ASSIGNED)).isZero();
    }

    @Test
    void assignmentIsTenantScopedAndUnknownClaimsAreNotFound() {
        Claim claim = createClaim();
        UUID otherTenant = UUID.fromString("99999999-9999-7999-8999-999999999999");

        assertThatThrownBy(() -> claimService.assign(otherTenant, claim.id(),
                request("intruder@example.com", "CASUALTY", claim.version()), null))
                .isInstanceOf(ClaimNotFoundException.class);
        assertThatThrownBy(() -> claimService.assign(TENANT, UUID.randomUUID(),
                request("intruder@example.com", "CASUALTY", claim.version()), null))
                .isInstanceOf(ClaimNotFoundException.class);

        Claim stored = claimService.get(TENANT, claim.id());
        assertThat(stored.assignedAdjuster()).isNull();
        assertThat(stored.version()).isZero();
        assertThat(countEvents(claim.id(), ClaimEvents.ASSIGNED)).isZero();
    }

    @Test
    void assignmentRollbackLeavesNoAssignmentAndNoEvent() {
        Claim claim = createClaim();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            claimService.assign(TENANT, claim.id(),
                    request("adjuster@example.com", "CASUALTY", claim.version()),
                    "supervisor@example.com");
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        Claim stored = claimService.get(TENANT, claim.id());
        assertThat(stored.assignedAdjuster()).isNull();
        assertThat(stored.assignedTeam()).isNull();
        assertThat(stored.assignedAt()).isNull();
        assertThat(stored.assignedBy()).isNull();
        assertThat(stored.version()).isZero();
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(countEvents(claim.id(), ClaimEvents.ASSIGNED)).isZero();
    }

    private Claim createClaim() {
        return claimService.create(new CreateClaimRequest(
                TENANT, POLICY_ID, CLAIMANT_ID,
                LocalDate.of(2026, 3, 14), LocalDate.of(2026, 3, 18),
                "GL", "PROPERTY_DAMAGE", new BigDecimal("0.00"), "USD",
                new BigDecimal("500000.00"), "USD", null));
    }

    private static ClaimAssignmentRequest request(String adjuster, String team, Integer version) {
        return new ClaimAssignmentRequest(adjuster, team, version);
    }

    private List<Map<String, Object>> events(UUID claimId) {
        return jdbc.queryForList("""
                SELECT event_type, aggregate_type, aggregate_version, payload, partition_key
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

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static long aggregateVersion(Map<String, Object> event) {
        return ((Number) event.get("aggregate_version")).longValue();
    }

    private static Map<String, Object> payload(Map<String, Object> event) {
        return EventJson.toMap((String) event.get("payload"));
    }
}
