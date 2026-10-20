package com.eia.directclaims.expert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository-level coverage for the tenant-scoped claim list: optional claimant
 * and status filters, their combination, and the newest-first ordering.
 */
@SpringBootTest
@ActiveProfiles("test")
class ClaimRepositoryTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID CLAIMANT = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID OTHER_CLAIMANT = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final UUID POLICY = UUID.fromString("22222222-2222-7222-8222-222222222222");

    private static final UUID FIRST_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0caa01");
    private static final UUID SECOND_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0caa02");
    private static final UUID THIRD_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0caa03");
    private static final UUID FOURTH_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0caa04");

    private static final Instant EARLY = Instant.parse("2026-03-18T08:00:00Z");
    private static final Instant MIDDLE = Instant.parse("2026-03-18T09:00:00Z");
    private static final Instant LATE = Instant.parse("2026-03-18T10:00:00Z");

    @Autowired
    private ClaimRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM claim");
    }

    @Test
    void findAllFiltersByTenantAndClaimantParty() {
        insert(FIRST_ID, TENANT, CLAIMANT, "CLM-2026-0000001", "REGISTERED", EARLY);
        insert(SECOND_ID, TENANT, OTHER_CLAIMANT, "CLM-2026-0000002", "REGISTERED", MIDDLE);
        insert(THIRD_ID, OTHER_TENANT, CLAIMANT, "CLM-2026-0000003", "REGISTERED", LATE);

        List<Claim> found = repository.findAll(TENANT, null, CLAIMANT);

        assertThat(found).extracting(Claim::id).containsExactly(FIRST_ID);
        assertThat(found).allSatisfy(claim -> {
            assertThat(claim.tenantId()).isEqualTo(TENANT);
            assertThat(claim.claimantPartyId()).isEqualTo(CLAIMANT);
            assertThat(claim.businessBook()).isEqualTo("DIRECT");
        });
    }

    @Test
    void findAllOrdersNewestFirstWithinTheTenant() {
        insert(FIRST_ID, TENANT, CLAIMANT, "CLM-2026-0000001", "REGISTERED", EARLY);
        insert(SECOND_ID, TENANT, OTHER_CLAIMANT, "CLM-2026-0000002", "DRAFT", LATE);
        insert(THIRD_ID, TENANT, CLAIMANT, "CLM-2026-0000003", "REGISTERED", MIDDLE);
        insert(FOURTH_ID, OTHER_TENANT, CLAIMANT, "CLM-2026-0000004", "REGISTERED", LATE);

        List<Claim> found = repository.findAll(TENANT, null, null);

        assertThat(found).extracting(Claim::id).containsExactly(SECOND_ID, THIRD_ID, FIRST_ID);
    }

    @Test
    void findAllKeepsTheStatusFilterWhenNoClaimantIsSupplied() {
        insert(FIRST_ID, TENANT, CLAIMANT, "CLM-2026-0000001", "REGISTERED", EARLY);
        insert(SECOND_ID, TENANT, OTHER_CLAIMANT, "CLM-2026-0000002", "DRAFT", MIDDLE);
        insert(THIRD_ID, TENANT, CLAIMANT, "CLM-2026-0000003", "REGISTERED", LATE);

        List<Claim> found = repository.findAll(TENANT, ClaimStatus.REGISTERED, null);

        assertThat(found).extracting(Claim::id).containsExactly(THIRD_ID, FIRST_ID);
    }

    @Test
    void findAllCombinesStatusAndClaimantParty() {
        insert(FIRST_ID, TENANT, CLAIMANT, "CLM-2026-0000001", "DRAFT", EARLY);
        insert(SECOND_ID, TENANT, CLAIMANT, "CLM-2026-0000002", "REGISTERED", MIDDLE);
        insert(THIRD_ID, TENANT, OTHER_CLAIMANT, "CLM-2026-0000003", "DRAFT", LATE);
        insert(FOURTH_ID, OTHER_TENANT, CLAIMANT, "CLM-2026-0000004", "DRAFT", LATE);

        List<Claim> found = repository.findAll(TENANT, ClaimStatus.DRAFT, CLAIMANT);

        assertThat(found).extracting(Claim::id).containsExactly(FIRST_ID);
    }

    private void insert(UUID id,
                        UUID tenantId,
                        UUID claimantPartyId,
                        String claimNumber,
                        String status,
                        Instant createdAt) {
        Timestamp timestamp = Timestamp.from(createdAt);
        jdbc.update("""
                INSERT INTO claim (id, tenant_id, business_book, claim_number, policy_id, claimant_party_id,
                                   status, loss_date, report_date, cause_code, claim_type, version,
                                   created_at, updated_at)
                VALUES (?, ?, 'DIRECT', ?, ?, ?, ?, ?, ?, 'GL', 'BODILY_INJURY', 0, ?, ?)
                """, id, tenantId, claimNumber, POLICY, claimantPartyId, status,
                Date.valueOf(LocalDate.of(2026, 3, 14)), Date.valueOf(LocalDate.of(2026, 3, 18)),
                timestamp, timestamp);
    }
}
