package com.eia.directclaims.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class LienRepository {
    private static final String COLUMNS = """
            id, tenant_id, claim_id, lien_type, lienholder, amount, currency, status,
            resolution_type, resolved_at, version, created_at, updated_at
            """;

    private static final RowMapper<Lien> MAPPER = (rs, rowNum) -> new Lien(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("claim_id", UUID.class),
            rs.getString("lien_type"),
            rs.getString("lienholder"),
            rs.getBigDecimal("amount"),
            rs.getString("currency") == null ? null : rs.getString("currency").trim(),
            rs.getString("status"),
            rs.getString("resolution_type"),
            rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public LienRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Lien> findAll(UUID tenantId, UUID claimId) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim_lien
                     WHERE tenant_id = ? AND claim_id = ?
                     ORDER BY created_at DESC, id ASC
                    """, MAPPER, tenantId, claimId);
    }

    public Optional<Lien> findById(UUID tenantId, UUID claimId, UUID lienId) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim_lien
                     WHERE tenant_id = ? AND claim_id = ? AND id = ?
                    """, MAPPER, tenantId, claimId, lienId).stream().findFirst();
    }

    public Lien insert(UUID id,
                       UUID tenantId,
                       UUID claimId,
                       String lienType,
                       String lienholder,
                       BigDecimal amount,
                       String currency,
                       String status,
                       String resolutionType,
                       Instant resolvedAt,
                       Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        jdbc.update("""
                INSERT INTO claim_lien (id, tenant_id, claim_id, lien_type, lienholder, amount, currency,
                                        status, resolution_type, resolved_at, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                """, id, tenantId, claimId, lienType, lienholder, amount, currency, status, resolutionType,
                resolvedAt == null ? null : Timestamp.from(resolvedAt), timestamp, timestamp);
        return findById(tenantId, claimId, id).orElseThrow();
    }

    public Lien update(UUID tenantId,
                       UUID claimId,
                       UUID lienId,
                       String status,
                       String resolutionType,
                       Instant resolvedAt,
                       int expectedVersion,
                       Instant now) {
        int changed = jdbc.update("""
                UPDATE claim_lien
                   SET status = ?, resolution_type = ?, resolved_at = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND claim_id = ? AND id = ? AND version = ?
                """, status, resolutionType, resolvedAt == null ? null : Timestamp.from(resolvedAt),
                Timestamp.from(now), tenantId, claimId, lienId, expectedVersion);
        if (changed == 0) {
            throw new ClaimConflictException("Lien " + lienId
                    + " was modified concurrently or version " + expectedVersion + " is stale");
        }
        return findById(tenantId, claimId, lienId).orElseThrow(() -> new ClaimNotFoundException(claimId));
    }

    /**
     * DIR-11: a lien is unresolved until it reaches a terminal status and the
     * resolution timestamp is recorded.
     */
    public long countUnresolved(UUID tenantId, UUID claimId) {
        Long value = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM claim_lien
                 WHERE tenant_id = ? AND claim_id = ?
                   AND NOT (status IN ('RESOLVED', 'WAIVED', 'PAID') AND resolved_at IS NOT NULL)
                """, Long.class, tenantId, claimId);
        return value == null ? 0L : value;
    }
}
