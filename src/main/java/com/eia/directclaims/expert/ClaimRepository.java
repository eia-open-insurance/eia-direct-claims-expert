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
            id, tenant_id, business_book, claim_number, policy_id, claimant_party_id,
            status, loss_date, report_date, cause_code, reserve_amount, reserve_currency,
            version, created_at, updated_at
            """;

    private static final RowMapper<Claim> MAPPER = (rs, rowNum) -> new Claim(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("business_book"),
            rs.getString("claim_number"),
            rs.getObject("policy_id", UUID.class),
            rs.getObject("claimant_party_id", UUID.class),
            rs.getString("status"),
            rs.getObject("loss_date", LocalDate.class),
            rs.getObject("report_date", LocalDate.class),
            rs.getString("cause_code"),
            rs.getBigDecimal("reserve_amount"),
            rs.getString("reserve_currency") == null ? null : rs.getString("reserve_currency").trim(),
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
                      FROM claim
                     WHERE tenant_id = ? AND business_book = 'DIRECT'
                     ORDER BY created_at DESC, claim_number DESC
                    """, MAPPER, tenantId);
        }
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim
                     WHERE tenant_id = ? AND business_book = 'DIRECT' AND status = ?
                     ORDER BY created_at DESC, claim_number DESC
                    """, MAPPER, tenantId, status.name());
    }

    public Optional<Claim> findById(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim
                     WHERE tenant_id = ? AND id = ? AND business_book = 'DIRECT'
                    """, MAPPER, tenantId, id).stream().findFirst();
    }

    public Claim insert(UUID id,
                        String claimNumber,
                        UUID tenantId,
                        UUID policyId,
                        UUID claimantPartyId,
                        ClaimStatus status,
                        LocalDate lossDate,
                        LocalDate reportDate,
                        String causeCode,
                        BigDecimal reserveAmount,
                        String reserveCurrency,
                        Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        return jdbc.queryForObject("""
                INSERT INTO claim (id, tenant_id, business_book, claim_number, policy_id, claimant_party_id,
                                   status, loss_date, report_date, cause_code, reserve_amount, reserve_currency,
                                   version, created_at, updated_at)
                VALUES (?, ?, 'DIRECT', ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                RETURNING id, tenant_id, business_book, claim_number, policy_id, claimant_party_id,
                          status, loss_date, report_date, cause_code, reserve_amount, reserve_currency,
                          version, created_at, updated_at
                """, MAPPER, id, tenantId, claimNumber, policyId, claimantPartyId, status.name(),
                lossDate, reportDate, causeCode, reserveAmount, reserveCurrency, timestamp, timestamp);
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
}
