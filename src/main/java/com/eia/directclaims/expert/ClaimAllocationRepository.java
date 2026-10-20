package com.eia.directclaims.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ClaimAllocationRepository {
    private static final String COLUMNS = """
            id, tenant_id, claim_id, policy_id, limit_id, policy_year, layer, allocation_pct,
            allocated_amount, erosion_status, erosion_message, erosion_posted_at, created_at
            """;

    private static final BigDecimal ZERO_PCT = new BigDecimal("0.000000");
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.0000");

    private static final RowMapper<ClaimAllocation> MAPPER = (rs, rowNum) -> new ClaimAllocation(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("claim_id", UUID.class),
            rs.getObject("policy_id", UUID.class),
            rs.getObject("limit_id", UUID.class),
            rs.getInt("policy_year"),
            rs.getString("layer"),
            rs.getBigDecimal("allocation_pct"),
            rs.getBigDecimal("allocated_amount"),
            rs.getString("erosion_status"),
            rs.getString("erosion_message"),
            instant(rs, "erosion_posted_at"),
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
                                  UUID limitId,
                                  int policyYear,
                                  String layer,
                                  BigDecimal allocationPct,
                                  BigDecimal allocatedAmount,
                                  Instant now) {
        jdbc.update("""
                INSERT INTO claim_allocation (id, tenant_id, claim_id, policy_id, limit_id, policy_year,
                                              layer, allocation_pct, allocated_amount, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, claimId, policyId, limitId, policyYear, layer, allocationPct,
                allocatedAmount, Timestamp.from(now));
        return findById(tenantId, claimId, id)
                .orElseThrow(() -> new IllegalStateException(
                        "Claim allocation " + id + " was not visible after insert"));
    }

    /**
     * Records a policy-erosion posting outcome. The {@code POSTED} state is
     * terminal: the predicate keeps a concurrent or repeated posting from
     * overwriting an allocation that is already posted. Zero updated rows means
     * the caller must re-read the row and decide between an idempotent no-op and
     * a conflict.
     */
    public Optional<ClaimAllocation> updateErosion(UUID tenantId,
                                                   UUID claimId,
                                                   UUID allocationId,
                                                   String status,
                                                   String message,
                                                   Instant postedAt) {
        int changed = jdbc.update("""
                UPDATE claim_allocation
                   SET erosion_status = ?, erosion_message = ?, erosion_posted_at = ?
                 WHERE tenant_id = ? AND claim_id = ? AND id = ?
                   AND erosion_status <> 'POSTED'
                """, status, message, postedAt == null ? null : Timestamp.from(postedAt),
                tenantId, claimId, allocationId);
        return changed == 0 ? Optional.empty() : findById(tenantId, claimId, allocationId);
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

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record Totals(BigDecimal pct, BigDecimal amount) {
    }
}
