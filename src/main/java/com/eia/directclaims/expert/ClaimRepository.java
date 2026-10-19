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
public class ClaimRepository {
    private static final String COLUMNS = """
            c.id, c.tenant_id, c.business_book, c.claim_number, c.policy_id, c.claimant_party_id,
            c.claim_type, c.status, c.loss_date, c.report_date, c.cause_code, c.reserve_amount,
            c.reserve_currency, c.settlement_authority_amount, c.settlement_authority_currency,
            c.litigation_flag, c.version, c.created_at, c.updated_at,
            COALESCE((SELECT SUM(r.amount) FROM claim_reserve_history r
                       WHERE r.claim_id = c.id AND r.is_current), 0)::numeric(19,4)
                AS current_reserve_amount
            """;

    private static final RowMapper<Claim> MAPPER = (rs, rowNum) -> new Claim(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("business_book"),
            rs.getString("claim_number"),
            rs.getObject("policy_id", UUID.class),
            rs.getObject("claimant_party_id", UUID.class),
            rs.getString("claim_type"),
            rs.getString("status"),
            rs.getObject("loss_date", LocalDate.class),
            rs.getObject("report_date", LocalDate.class),
            rs.getString("cause_code"),
            rs.getBigDecimal("reserve_amount"),
            rs.getString("reserve_currency") == null ? null : rs.getString("reserve_currency").trim(),
            rs.getBigDecimal("settlement_authority_amount"),
            rs.getString("settlement_authority_currency") == null
                    ? null : rs.getString("settlement_authority_currency").trim(),
            rs.getBoolean("litigation_flag"),
            rs.getBigDecimal("current_reserve_amount"),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public ClaimRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long nextClaimSequence() {
        Long value = jdbc.queryForObject("SELECT nextval('claim_number_seq')", Long.class);
        return value == null ? 1L : value;
    }

    public List<Claim> findAll(UUID tenantId, ClaimStatus status) {
        if (status == null) {
            return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim c
                     WHERE c.tenant_id = ? AND c.business_book = 'DIRECT'
                     ORDER BY c.created_at DESC, c.claim_number DESC
                    """, MAPPER, tenantId);
        }
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim c
                     WHERE c.tenant_id = ? AND c.business_book = 'DIRECT' AND c.status = ?
                     ORDER BY c.created_at DESC, c.claim_number DESC
                    """, MAPPER, tenantId, status.name());
    }

    public Optional<Claim> findById(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim c
                     WHERE c.tenant_id = ? AND c.id = ? AND c.business_book = 'DIRECT'
                    """, MAPPER, tenantId, id).stream().findFirst();
    }

    public Claim insert(UUID id,
                        String claimNumber,
                        UUID tenantId,
                        UUID policyId,
                        UUID claimantPartyId,
                        String claimType,
                        ClaimStatus status,
                        LocalDate lossDate,
                        LocalDate reportDate,
                        String causeCode,
                        BigDecimal reserveAmount,
                        String reserveCurrency,
                        BigDecimal settlementAuthorityAmount,
                        String settlementAuthorityCurrency,
                        Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        jdbc.update("""
                INSERT INTO claim (id, tenant_id, business_book, claim_number, policy_id, claimant_party_id,
                                   claim_type, status, loss_date, report_date, cause_code, reserve_amount,
                                   reserve_currency, settlement_authority_amount, settlement_authority_currency,
                                   litigation_flag, version, created_at, updated_at)
                VALUES (?, ?, 'DIRECT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, 0, ?, ?)
                """, id, tenantId, claimNumber, policyId, claimantPartyId, claimType, status.name(),
                lossDate, reportDate, causeCode, reserveAmount, reserveCurrency,
                settlementAuthorityAmount, settlementAuthorityCurrency, timestamp, timestamp);
        return findById(tenantId, id).orElseThrow(() -> new ClaimNotFoundException(id));
    }

    public Claim updateStatus(UUID tenantId, UUID id, ClaimStatus status, int expectedVersion, Instant now) {
        int changed = jdbc.update("""
                UPDATE claim
                   SET status = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND id = ? AND version = ? AND business_book = 'DIRECT'
                """, status.name(), Timestamp.from(now), tenantId, id, expectedVersion);
        if (changed == 0) {
            throw new ClaimConflictException(
                    "Claim " + id + " was modified concurrently or version " + expectedVersion + " is stale");
        }
        return findById(tenantId, id).orElseThrow(() -> new ClaimNotFoundException(id));
    }

    /**
     * Locks the claim row by bumping its version, used by the append-only
     * reserve history and the coverage decision in the same transaction.
     */
    public int bumpVersion(UUID tenantId, UUID id, int expectedVersion, Instant now) {
        int changed = jdbc.update("""
                UPDATE claim
                   SET version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND id = ? AND version = ? AND business_book = 'DIRECT'
                """, Timestamp.from(now), tenantId, id, expectedVersion);
        if (changed == 0) {
            throw new ClaimConflictException(
                    "Claim " + id + " was modified concurrently or version " + expectedVersion + " is stale");
        }
        return expectedVersion + 1;
    }

    public void markLitigation(UUID tenantId, UUID id, Instant now) {
        jdbc.update("""
                UPDATE claim
                   SET litigation_flag = TRUE, updated_at = ?
                 WHERE tenant_id = ? AND id = ? AND business_book = 'DIRECT'
                """, Timestamp.from(now), tenantId, id);
    }
}
