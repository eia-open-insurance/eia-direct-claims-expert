package com.eia.directclaims.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RecoveryRepository {
    private static final String COLUMNS = """
            id, tenant_id, claim_id, recovery_type, amount, currency, source, status,
            recovery_date, version, created_at, updated_at
            """;

    private static final RowMapper<Recovery> MAPPER = (rs, rowNum) -> new Recovery(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("claim_id", UUID.class),
            rs.getString("recovery_type"),
            rs.getBigDecimal("amount"),
            rs.getString("currency") == null ? null : rs.getString("currency").trim(),
            rs.getString("source"),
            rs.getString("status"),
            rs.getObject("recovery_date", LocalDate.class),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public RecoveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Recovery> findAll(UUID tenantId, UUID claimId) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim_recovery
                     WHERE tenant_id = ? AND claim_id = ?
                     ORDER BY created_at DESC, id ASC
                    """, MAPPER, tenantId, claimId);
    }

    public Optional<Recovery> findById(UUID tenantId, UUID claimId, UUID recoveryId) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim_recovery
                     WHERE tenant_id = ? AND claim_id = ? AND id = ?
                    """, MAPPER, tenantId, claimId, recoveryId).stream().findFirst();
    }

    public Recovery insert(UUID id,
                           UUID tenantId,
                           UUID claimId,
                           String recoveryType,
                           BigDecimal amount,
                           String currency,
                           String source,
                           String status,
                           LocalDate recoveryDate,
                           Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        jdbc.update("""
                INSERT INTO claim_recovery (id, tenant_id, claim_id, recovery_type, amount, currency,
                                            source, status, recovery_date, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                """, id, tenantId, claimId, recoveryType, amount, currency, source, status, recoveryDate,
                timestamp, timestamp);
        return findById(tenantId, claimId, id).orElseThrow();
    }

    public Recovery updateStatus(UUID tenantId,
                                 UUID claimId,
                                 UUID recoveryId,
                                 String status,
                                 LocalDate recoveryDate,
                                 int expectedVersion,
                                 Instant now) {
        int changed = jdbc.update("""
                UPDATE claim_recovery
                   SET status = ?, recovery_date = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND claim_id = ? AND id = ? AND version = ?
                """, status, recoveryDate, Timestamp.from(now), tenantId, claimId, recoveryId, expectedVersion);
        if (changed == 0) {
            throw new ClaimConflictException("Recovery " + recoveryId
                    + " was modified concurrently or version " + expectedVersion + " is stale");
        }
        return findById(tenantId, claimId, recoveryId).orElseThrow(() -> new ClaimNotFoundException(claimId));
    }

    /** Received recoveries, optionally excluding the row being validated. */
    public BigDecimal receivedTotal(UUID tenantId, UUID claimId, UUID excludedRecoveryId) {
        BigDecimal value = excludedRecoveryId == null
                ? jdbc.queryForObject("""
                        SELECT COALESCE(SUM(amount), 0)::numeric(19,4)
                          FROM claim_recovery
                         WHERE tenant_id = ? AND claim_id = ? AND status = 'RECEIVED'
                        """, BigDecimal.class, tenantId, claimId)
                : jdbc.queryForObject("""
                        SELECT COALESCE(SUM(amount), 0)::numeric(19,4)
                          FROM claim_recovery
                         WHERE tenant_id = ? AND claim_id = ? AND status = 'RECEIVED' AND id <> ?
                        """, BigDecimal.class, tenantId, claimId, excludedRecoveryId);
        return value == null ? new BigDecimal("0.0000") : value;
    }
}
