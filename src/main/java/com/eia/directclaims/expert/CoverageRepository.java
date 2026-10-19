package com.eia.directclaims.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CoverageRepository {
    private static final String COLUMNS = """
            id, tenant_id, claim_id, coverage_position, basis, policy_provisions, allocation_method,
            allocation_pct, decision_date, reviewer_party_id, status, version, created_at, updated_at
            """;

    private static final RowMapper<CoverageDecision> MAPPER = (rs, rowNum) -> new CoverageDecision(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("claim_id", UUID.class),
            rs.getString("coverage_position"),
            rs.getString("basis"),
            rs.getString("policy_provisions"),
            rs.getString("allocation_method"),
            rs.getBigDecimal("allocation_pct"),
            rs.getObject("decision_date", LocalDate.class),
            rs.getObject("reviewer_party_id", UUID.class),
            rs.getString("status"),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;
    private final ClaimRepository claimRepository;

    public CoverageRepository(JdbcTemplate jdbc, ClaimRepository claimRepository) {
        this.jdbc = jdbc;
        this.claimRepository = claimRepository;
    }

    public Optional<CoverageDecision> findLatestFinal(UUID tenantId, UUID claimId) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim_coverage_decision
                     WHERE tenant_id = ? AND claim_id = ? AND status = 'FINAL'
                     ORDER BY created_at DESC
                     LIMIT 1
                    """, MAPPER, tenantId, claimId).stream().findFirst();
    }

    /**
     * Records a new final decision: the prior final row is superseded and the
     * new row inserted in one transaction, preserving the decision history.
     */
    @Transactional
    public CoverageDecision decide(Claim claim,
                                   String position,
                                   String basis,
                                   String policyProvisions,
                                   String allocationMethod,
                                   BigDecimal allocationPct,
                                   LocalDate decisionDate,
                                   UUID reviewerPartyId,
                                   Instant now) {
        claimRepository.bumpVersion(claim.tenantId(), claim.id(), claim.version(), now);
        jdbc.update("""
                UPDATE claim_coverage_decision
                   SET status = 'SUPERSEDED', updated_at = ?
                 WHERE claim_id = ? AND status = 'FINAL'
                """, Timestamp.from(now), claim.id());
        UUID id = UuidV7.next();
        jdbc.update("""
                INSERT INTO claim_coverage_decision (id, tenant_id, claim_id, coverage_position, basis,
                                                     policy_provisions, allocation_method, allocation_pct,
                                                     decision_date, reviewer_party_id, status, version,
                                                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'FINAL', 1, ?, ?)
                """, id, claim.tenantId(), claim.id(), position, basis, policyProvisions, allocationMethod,
                allocationPct, decisionDate, reviewerPartyId, Timestamp.from(now), Timestamp.from(now));
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim_coverage_decision
                     WHERE id = ?
                    """, MAPPER, id).stream().findFirst().orElseThrow();
    }
}
