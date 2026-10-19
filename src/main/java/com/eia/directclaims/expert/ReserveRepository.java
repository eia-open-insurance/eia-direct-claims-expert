package com.eia.directclaims.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public class ReserveRepository {
    private static final String COLUMNS = """
            id, tenant_id, claim_id, component, amount, currency, effective_date, reason_code,
            reason_text, authority, sequence, is_current, version, created_at
            """;

    private static final RowMapper<ReserveHistoryEntry> MAPPER = (rs, rowNum) -> new ReserveHistoryEntry(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("claim_id", UUID.class),
            rs.getString("component"),
            rs.getBigDecimal("amount"),
            rs.getString("currency") == null ? null : rs.getString("currency").trim(),
            rs.getObject("effective_date", LocalDate.class),
            rs.getString("reason_code"),
            rs.getString("reason_text"),
            rs.getString("authority"),
            rs.getInt("sequence"),
            rs.getBoolean("is_current"),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbc;
    private final ClaimRepository claimRepository;

    public ReserveRepository(JdbcTemplate jdbc, ClaimRepository claimRepository) {
        this.jdbc = jdbc;
        this.claimRepository = claimRepository;
    }

    public List<ReserveHistoryEntry> findHistory(UUID tenantId, UUID claimId) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim_reserve_history
                     WHERE tenant_id = ? AND claim_id = ?
                     ORDER BY created_at DESC, sequence DESC
                    """, MAPPER, tenantId, claimId);
    }

    public List<ReserveHistoryEntry> findCurrent(UUID tenantId, UUID claimId) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim_reserve_history
                     WHERE tenant_id = ? AND claim_id = ? AND is_current
                     ORDER BY component ASC
                    """, MAPPER, tenantId, claimId);
    }

    public BigDecimal currentTotal(UUID tenantId, UUID claimId) {
        BigDecimal value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0)::numeric(19,4)
                  FROM claim_reserve_history
                 WHERE tenant_id = ? AND claim_id = ? AND is_current
                """, BigDecimal.class, tenantId, claimId);
        return value == null ? new BigDecimal("0.0000") : value;
    }

    public int nextSequence(UUID claimId, String component) {
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(sequence), 0) + 1
                  FROM claim_reserve_history
                 WHERE claim_id = ? AND component = ?
                """, Integer.class, claimId, component);
        return value == null ? 1 : value;
    }

    /**
     * Append-only change: bumps the claim version, closes the previous current
     * row for the component, and inserts the new current row. All three writes
     * commit together.
     */
    @Transactional
    public ReserveHistoryEntry postChange(Claim claim,
                                          String component,
                                          BigDecimal amount,
                                          String currency,
                                          LocalDate effectiveDate,
                                          String reasonCode,
                                          String reasonText,
                                          String authority,
                                          int expectedVersion,
                                          Instant now) {
        int version = claimRepository.bumpVersion(claim.tenantId(), claim.id(), expectedVersion, now);
        int sequence = nextSequence(claim.id(), component);
        jdbc.update("""
                UPDATE claim_reserve_history
                   SET is_current = FALSE
                 WHERE claim_id = ? AND component = ? AND is_current
                """, claim.id(), component);
        UUID id = UuidV7.next();
        jdbc.update("""
                INSERT INTO claim_reserve_history (id, tenant_id, claim_id, component, amount, currency,
                                                  effective_date, reason_code, reason_text, authority,
                                                  sequence, is_current, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?)
                """, id, claim.tenantId(), claim.id(), component, amount, currency, effectiveDate,
                reasonCode, reasonText, authority, sequence, version, Timestamp.from(now));
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim_reserve_history
                     WHERE id = ?
                    """, MAPPER, id).stream().findFirst().orElseThrow();
    }

    /**
     * Seeds the reserve supplied at registration as the first posted history
     * row so the derived current reserve is consistent from the start.
     */
    public void seedInitial(UUID tenantId, UUID claimId, BigDecimal amount, String currency, Instant now) {
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO claim_reserve_history (id, tenant_id, claim_id, component, amount, currency,
                                                  effective_date, reason_code, reason_text, authority,
                                                  sequence, is_current, version, created_at)
                VALUES (?, ?, ?, 'INDEMNITY', ?, ?, ?, 'INITIAL', ?, 'SYSTEM', 1, TRUE, 0, ?)
                """, UuidV7.next(), tenantId, claimId, amount, currency, now.atZone(java.time.ZoneOffset.UTC)
                .toLocalDate(), "Initial reserve supplied at registration", Timestamp.from(now));
    }
}
