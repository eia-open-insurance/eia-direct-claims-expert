package com.eia.directclaims.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ClaimAllocationRepository {
    private static final String COLUMNS = """
            id, tenant_id, claim_id, policy_id, policy_year, layer, allocation_pct,
            allocated_amount, created_at
            """;

    private static final BigDecimal ZERO_PCT = new BigDecimal("0.000000");
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.0000");

    private static final RowMapper<ClaimAllocation> MAPPER = (rs, rowNum) -> new ClaimAllocation(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("claim_id", UUID.class),
            rs.getObject("policy_id", UUID.class),
            rs.getInt("policy_year"),
            rs.getString("layer"),
            rs.getBigDecimal("allocation_pct"),
            rs.getBigDecimal("allocated_amount"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public ClaimAllocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Oldest first. {@code id} (UUIDv7) breaks ties for rows written with the
     * same clock instant, keeping the sequence stable.
     */
    public List<ClaimAllocation> findAll(UUID tenantId, UUID claimId) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim_allocation
                     WHERE tenant_id = ? AND claim_id = ?
                     ORDER BY created_at ASC, id ASC
                    """, MAPPER, tenantId, claimId);
    }

    public Optional<ClaimAllocation> findById(UUID tenantId, UUID claimId, UUID allocationId) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim_allocation
                     WHERE tenant_id = ? AND claim_id = ? AND id = ?
                    """, MAPPER, tenantId, claimId, allocationId).stream().findFirst();
    }

    public ClaimAllocation insert(UUID id,
                                  UUID tenantId,
                                  UUID claimId,
                                  UUID policyId,
                                  int policyYear,
                                  String layer,
                                  BigDecimal allocationPct,
                                  BigDecimal allocatedAmount,
                                  Instant now) {
        jdbc.update("""
                INSERT INTO claim_allocation (id, tenant_id, claim_id, policy_id, policy_year, layer,
                                              allocation_pct, allocated_amount, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, claimId, policyId, policyYear, layer, allocationPct,
                allocatedAmount, Timestamp.from(now));
        return findById(tenantId, claimId, id)
                .orElseThrow(() -> new IllegalStateException(
                        "Claim allocation " + id + " was not visible after insert"));
    }

    /**
     * Sum of the allocation percentages already recorded for the claim. The
     * create command adds the candidate percentage to this value and rejects a
     * total above 1 before writing.
     */
    public BigDecimal totalPct(UUID tenantId, UUID claimId) {
        return totals(tenantId, claimId).pct();
    }

    /**
     * Current totals for the claim, used for the list summary and the create
     * event payload.
     */
    public Totals totals(UUID tenantId, UUID claimId) {
        Totals totals = jdbc.queryForObject("""
                SELECT COALESCE(SUM(allocation_pct), 0) AS total_pct,
                       COALESCE(SUM(allocated_amount), 0) AS total_amount
                  FROM claim_allocation
                 WHERE tenant_id = ? AND claim_id = ?
                """, (rs, rowNum) -> new Totals(
                        scalePct(rs.getBigDecimal("total_pct")),
                        scaleAmount(rs.getBigDecimal("total_amount"))), tenantId, claimId);
        return totals == null ? new Totals(ZERO_PCT, ZERO_AMOUNT) : totals;
    }

    static BigDecimal scalePct(BigDecimal value) {
        return value == null ? ZERO_PCT : value.setScale(6, RoundingMode.HALF_UP);
    }

    static BigDecimal scaleAmount(BigDecimal value) {
        return value == null ? ZERO_AMOUNT : value.setScale(4, RoundingMode.HALF_UP);
    }

    public record Totals(BigDecimal pct, BigDecimal amount) {
    }
}
