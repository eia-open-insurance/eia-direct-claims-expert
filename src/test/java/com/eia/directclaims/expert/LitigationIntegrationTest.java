package com.eia.directclaims.expert;

import com.eia.platform.libs.events.EventJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Litigation matter persistence, optional damages, validation, tenant scoping,
 * DDL constraints, and outbox payloads against the H2 test schema.
 */
@SpringBootTest
@ActiveProfiles("test")
class LitigationIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID POLICY_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID CLAIMANT_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID COUNSEL_ID = UUID.fromString("44444444-4444-7444-8444-444444444444");

    @Autowired
    private ClaimService claimService;
    @Autowired
    private LitigationService litigationService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM claim_litigation");
        jdbc.update("DELETE FROM claim");
    }

    @Test
    void createsAMatterWithDamagesAndEmitsThemInTheCreatedEvent() {
        Claim claim = createClaim();

        LitigationMatter created = litigationService.add(TENANT, claim.id(),
                request("275000.00", "usd"));

        assertThat(created.damagesSought()).isEqualByComparingTo("275000.0000");
        assertThat(created.damagesCurrency()).isEqualTo("USD");
        assertThat(created.version()).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT damages_sought, damages_currency FROM claim_litigation
                 WHERE tenant_id = ? AND claim_id = ? AND id = ?
                """, TENANT, claim.id(), created.id());
        assertThat((BigDecimal) row.get("damages_sought")).isEqualByComparingTo("275000.0000");
        assertThat(row.get("damages_currency")).isEqualTo("USD");

        assertThat(claimVersion(claim.id())).isEqualTo(1);
        Map<String, Object> event = lastEvent(claim.id());
        assertThat(event.get("event_type")).isEqualTo("direct.claim.litigation-created");
        assertThat(aggregateVersion(event)).isEqualTo(2L);
        Map<String, Object> payload = payload(event);
        assertThat(payload.get("litigationId")).isEqualTo(created.id().toString());
        assertThat(payload.get("litigationStatus")).isEqualTo("ACTIVE");
        assertThat(payload.get("litigationVersion")).isEqualTo(1);
        assertThat(payload.get("damagesSought")).isEqualTo("275000.0000");
        assertThat(payload.get("damagesCurrency")).isEqualTo("USD");
    }

    @Test
    void createsAMatterWithoutDamagesAndOmitsThemFromTheCreatedEvent() {
        Claim claim = createClaim();

        LitigationMatter created = litigationService.add(TENANT, claim.id(), request(null, null));

        assertThat(created.damagesSought()).isNull();
        assertThat(created.damagesCurrency()).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM claim_litigation
                 WHERE id = ? AND damages_sought IS NULL AND damages_currency IS NULL
                """, Integer.class, created.id())).isEqualTo(1);

        Map<String, Object> payload = payload(lastEvent(claim.id()));
        assertThat(payload).doesNotContainKeys("damagesSought", "damagesCurrency");
    }

    @Test
    void updatesDamagesAndEmitsThemInTheUpdatedEvent() {
        Claim claim = createClaim();
        LitigationMatter created = litigationService.add(TENANT, claim.id(), request(null, null));

        LitigationMatter updated = litigationService.update(TENANT, claim.id(), created.id(),
                updateRequest("ACTIVE", "325000.50", "USD", 1));

        assertThat(updated.damagesSought()).isEqualByComparingTo("325000.5000");
        assertThat(updated.damagesCurrency()).isEqualTo("USD");
        assertThat(updated.version()).isEqualTo(2);
        assertThat(claimVersion(claim.id())).isEqualTo(2);

        Map<String, Object> event = lastEvent(claim.id());
        assertThat(event.get("event_type")).isEqualTo("direct.claim.litigation-updated");
        assertThat(aggregateVersion(event)).isEqualTo(3L);
        Map<String, Object> payload = payload(event);
        assertThat(payload.get("litigationVersion")).isEqualTo(2);
        assertThat(payload.get("damagesSought")).isEqualTo("325000.5000");
        assertThat(payload.get("damagesCurrency")).isEqualTo("USD");
    }

    @Test
    void updateWithoutDamagesKeepsTheStoredPair() {
        Claim claim = createClaim();
        LitigationMatter created = litigationService.add(TENANT, claim.id(),
                request("100000.00", "USD"));

        LitigationMatter updated = litigationService.update(TENANT, claim.id(), created.id(),
                updateRequest("STAYED", null, null, 1));

        assertThat(updated.damagesSought()).isEqualByComparingTo("100000.0000");
        assertThat(updated.damagesCurrency()).isEqualTo("USD");
        assertThat(updated.status()).isEqualTo("STAYED");

        Map<String, Object> stored = jdbc.queryForMap("""
                SELECT damages_sought, damages_currency FROM claim_litigation WHERE id = ?
                """, created.id());
        assertThat((BigDecimal) stored.get("damages_sought")).isEqualByComparingTo("100000.0000");
        assertThat(stored.get("damages_currency")).isEqualTo("USD");

        Map<String, Object> payload = payload(lastEvent(claim.id()));
        assertThat(payload.get("damagesSought")).isEqualTo("100000.0000");
        assertThat(payload.get("damagesCurrency")).isEqualTo("USD");
    }

    @Test
    void rejectsAnUnpairedDamagesChangeOnCreateAndUpdate() {
        Claim claim = createClaim();

        assertThatThrownBy(() -> litigationService.add(TENANT, claim.id(),
                request("1000.00", null)))
                .isInstanceOf(ClaimValidationException.class)
                .hasMessageContaining("damagesSought")
                .hasMessageContaining("damagesCurrency");
        assertThatThrownBy(() -> litigationService.add(TENANT, claim.id(),
                request(null, "USD")))
                .isInstanceOf(ClaimValidationException.class)
                .hasMessageContaining("damagesSought")
                .hasMessageContaining("damagesCurrency");
        assertThat(count("claim_litigation")).isZero();
        assertThat(claimVersion(claim.id())).isZero();

        LitigationMatter created = litigationService.add(TENANT, claim.id(), request(null, null));

        assertThatThrownBy(() -> litigationService.update(TENANT, claim.id(), created.id(),
                updateRequest("ACTIVE", "5000.00", null, 1)))
                .isInstanceOf(ClaimValidationException.class);
        assertThatThrownBy(() -> litigationService.update(TENANT, claim.id(), created.id(),
                updateRequest("ACTIVE", null, "USD", 1)))
                .isInstanceOf(ClaimValidationException.class);

        LitigationMatter stored = litigationService.list(TENANT, claim.id()).get(0);
        assertThat(stored.damagesSought()).isNull();
        assertThat(stored.damagesCurrency()).isNull();
        assertThat(claimVersion(claim.id())).isEqualTo(1);
        assertThat(countEvents(claim.id(), "direct.claim.litigation-updated")).isZero();
    }

    @Test
    void rejectsNonPositiveDamagesOnCreateAndUpdate() {
        Claim claim = createClaim();

        assertThatThrownBy(() -> litigationService.add(TENANT, claim.id(), request("0.00", "USD")))
                .isInstanceOf(ClaimValidationException.class)
                .hasMessageContaining("greater than zero");
        assertThatThrownBy(() -> litigationService.add(TENANT, claim.id(), request("-0.01", "USD")))
                .isInstanceOf(ClaimValidationException.class)
                .hasMessageContaining("greater than zero");
        assertThat(count("claim_litigation")).isZero();
        assertThat(claimVersion(claim.id())).isZero();

        LitigationMatter created = litigationService.add(TENANT, claim.id(), request(null, null));

        assertThatThrownBy(() -> litigationService.update(TENANT, claim.id(), created.id(),
                updateRequest("ACTIVE", "0.00", "USD", 1)))
                .isInstanceOf(ClaimValidationException.class)
                .hasMessageContaining("greater than zero");
        assertThat(claimVersion(claim.id())).isEqualTo(1);
        assertThat(countEvents(claim.id(), "direct.claim.litigation-updated")).isZero();
    }

    @Test
    void hidesMattersFromAnotherTenantAndAnotherClaim() {
        Claim claim = createClaim();
        LitigationMatter created = litigationService.add(TENANT, claim.id(), request("75000.00", "USD"));

        assertThatThrownBy(() -> litigationService.list(OTHER_TENANT, claim.id()))
                .isInstanceOf(ClaimNotFoundException.class);
        assertThatThrownBy(() -> litigationService.add(OTHER_TENANT, claim.id(), request(null, null)))
                .isInstanceOf(ClaimNotFoundException.class);
        assertThatThrownBy(() -> litigationService.update(OTHER_TENANT, claim.id(), created.id(),
                updateRequest("STAYED", null, null, 1)))
                .isInstanceOf(ClaimNotFoundException.class);

        Claim otherClaim = createClaim();
        assertThatThrownBy(() -> litigationService.update(TENANT, otherClaim.id(), created.id(),
                updateRequest("STAYED", null, null, 1)))
                .isInstanceOf(RecordNotFoundException.class);

        assertThat(count("claim_litigation")).isEqualTo(1);
        LitigationMatter stored = litigationService.list(TENANT, claim.id()).get(0);
        assertThat(stored.status()).isEqualTo("ACTIVE");
        assertThat(stored.damagesSought()).isEqualByComparingTo("75000.0000");
    }

    @Test
    void databaseRejectsAnUnpairedOrNonPositiveDamagesPair() {
        Claim claim = createClaim();

        assertThatThrownBy(() -> insertDirectly(claim.id(), new BigDecimal("1000.0000"), null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertDirectly(claim.id(), null, "USD"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertDirectly(claim.id(), new BigDecimal("0.0000"), "USD"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertDirectly(claim.id(), new BigDecimal("-1.0000"), "USD"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("claim_litigation")).isZero();

        insertDirectly(claim.id(), new BigDecimal("1000.0000"), "USD");
        assertThat(count("claim_litigation")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers
    private Claim createClaim() {
        return claimService.create(new CreateClaimRequest(
                TENANT, POLICY_ID, CLAIMANT_ID,
                LocalDate.of(2026, 3, 14), LocalDate.of(2026, 3, 18),
                "GL", "PROPERTY_DAMAGE", new BigDecimal("125000.00"), "USD",
                new BigDecimal("500000.00"), "USD", null));
    }

    private static LitigationRequest request(String damagesSought, String damagesCurrency) {
        return new LitigationRequest("DEFENSE", "US-OH", COUNSEL_ID,
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 9, 1), "ACTIVE", "CV-2026-001",
                damagesSought == null ? null : new BigDecimal(damagesSought), damagesCurrency, null);
    }

    private static LitigationRequest updateRequest(String status, String damagesSought,
                                                   String damagesCurrency, int expectedVersion) {
        return new LitigationRequest(null, null, null, null, null, status, null,
                damagesSought == null ? null : new BigDecimal(damagesSought), damagesCurrency,
                expectedVersion);
    }

    private void insertDirectly(UUID claimId, BigDecimal damagesSought, String damagesCurrency) {
        jdbc.update("""
                INSERT INTO claim_litigation (id, tenant_id, claim_id, matter_type, jurisdiction,
                                              counsel_party_id, filing_date, status, damages_sought,
                                              damages_currency, version, created_at, updated_at)
                VALUES (?, ?, ?, 'DEFENSE', 'US-OH', ?, DATE '2026-02-01', 'ACTIVE', ?, ?, 1,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), TENANT, claimId, COUNSEL_ID, damagesSought, damagesCurrency);
    }

    private int claimVersion(UUID claimId) {
        return jdbc.queryForObject("SELECT version FROM claim WHERE id = ?", Integer.class, claimId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private List<Map<String, Object>> events(UUID claimId) {
        return jdbc.queryForList("""
                SELECT event_type, aggregate_version, payload
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
}
