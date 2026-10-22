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
public class SettlementRepository {
    private static final String COLUMNS = """
            id, tenant_id, claim_id, settlement_number, amount, currency, structure, release_scope,
            approval_authority, payment_instructions, release_document_id, status, authorized_by,
            authorized_at, executed_at, payment_id, payment_linked_at, version, created_at, updated_at
            """;

    private static final RowMapper<Settlement> MAPPER = (rs, rowNum) -> new Settlement(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("claim_id", UUID.class),
            rs.getString("settlement_number"),
            rs.getBigDecimal("amount"),
            rs.getString("currency") == null ? null : rs.getString("currency").trim(),
            rs.getString("structure"),
            rs.getString("release_scope"),
            rs.getString("approval_authority"),
            rs.getString("payment_instructions"),
            rs.getObject("release_document_id", UUID.class),
            rs.getString("status"),
            rs.getObject("authorized_by", UUID.class),
            rs.getTimestamp("authorized_at") == null ? null : rs.getTimestamp("authorized_at").toInstant(),
            rs.getTimestamp("executed_at") == null ? null : rs.getTimestamp("executed_at").toInstant(),
            rs.getObject("payment_id", UUID.class),
            rs.getTimestamp("payment_linked_at") == null
                    ? null : rs.getTimestamp("payment_linked_at").toInstant(),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public SettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long nextSettlementSequence() {
        Long value = jdbc.queryForObject("SELECT nextval('settlement_number_seq')", Long.class);
        return value == null ? 1L : value;
    }

    public List<Settlement> findAll(UUID tenantId, UUID claimId) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim_settlement
                     WHERE tenant_id = ? AND claim_id = ?
                     ORDER BY created_at DESC, settlement_number DESC
                    """, MAPPER, tenantId, claimId);
    }

    public Optional<Settlement> findById(UUID tenantId, UUID claimId, UUID settlementId) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim_settlement
                     WHERE tenant_id = ? AND claim_id = ? AND id = ?
                    """, MAPPER, tenantId, claimId, settlementId).stream().findFirst();
    }

    public Settlement insert(UUID id,
                             UUID tenantId,
                             UUID claimId,
                             String settlementNumber,
                             BigDecimal amount,
                             String currency,
                             String structure,
                             String releaseScope,
                             String paymentInstructions,
                             Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        jdbc.update("""
                INSERT INTO claim_settlement (id, tenant_id, claim_id, settlement_number, amount, currency,
                                              structure, release_scope, payment_instructions, status,
                                              version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROPOSED', 1, ?, ?)
                """, id, tenantId, claimId, settlementNumber, amount, currency, structure, releaseScope,
                paymentInstructions, timestamp, timestamp);
        return findById(tenantId, claimId, id).orElseThrow();
    }

    public Settlement authorize(UUID tenantId,
                                UUID claimId,
                                UUID settlementId,
                                String approvalAuthority,
                                UUID authorizedBy,
                                int expectedVersion,
                                Instant now) {
        int changed = jdbc.update("""
                UPDATE claim_settlement
                   SET status = 'AUTHORIZED', approval_authority = ?, authorized_by = ?, authorized_at = ?,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND claim_id = ? AND id = ? AND version = ? AND status = 'PROPOSED'
                """, approvalAuthority, authorizedBy, Timestamp.from(now), Timestamp.from(now),
                tenantId, claimId, settlementId, expectedVersion);
        if (changed == 0) {
            throw new ClaimConflictException("Settlement " + settlementId
                    + " is not PROPOSED at version " + expectedVersion
                    + " or was modified concurrently");
        }
        return findById(tenantId, claimId, settlementId).orElseThrow(() -> new ClaimNotFoundException(claimId));
    }

    public Settlement execute(UUID tenantId,
                              UUID claimId,
                              UUID settlementId,
                              UUID releaseDocumentId,
                              String paymentInstructions,
                              int expectedVersion,
                              Instant now) {
        int changed = jdbc.update("""
                UPDATE claim_settlement
                   SET status = 'EXECUTED', release_document_id = ?, payment_instructions = ?,
                       executed_at = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND claim_id = ? AND id = ? AND version = ? AND status = 'AUTHORIZED'
                """, releaseDocumentId, paymentInstructions, Timestamp.from(now), Timestamp.from(now),
                tenantId, claimId, settlementId, expectedVersion);
        if (changed == 0) {
            throw new ClaimConflictException("Settlement " + settlementId
                    + " is not AUTHORIZED at version " + expectedVersion
                    + " or was modified concurrently");
        }
        return findById(tenantId, claimId, settlementId).orElseThrow(() -> new ClaimNotFoundException(claimId));
    }

    /**
     * Sets or clears the external Payment link. A non-null {@code paymentId}
     * stamps {@code paymentLinkedAt} with {@code linkedAt}; a null payment id
     * clears both columns. The version predicate and the status predicate make
     * the update safe against a concurrent settlement change.
     */
    public Settlement updatePayment(UUID tenantId,
                                    UUID claimId,
                                    UUID settlementId,
                                    UUID paymentId,
                                    Instant linkedAt,
                                    int expectedVersion,
                                    Instant now) {
        int changed = jdbc.update("""
                UPDATE claim_settlement
                   SET payment_id = ?, payment_linked_at = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND claim_id = ? AND id = ? AND version = ?
                   AND status IN ('AUTHORIZED', 'EXECUTED')
                """, paymentId, linkedAt == null ? null : Timestamp.from(linkedAt), Timestamp.from(now),
                tenantId, claimId, settlementId, expectedVersion);
        if (changed == 0) {
            throw new ClaimConflictException("Settlement " + settlementId
                    + " is not AUTHORIZED or EXECUTED at version " + expectedVersion
                    + " or was modified concurrently");
        }
        return findById(tenantId, claimId, settlementId)
                .orElseThrow(() -> new ClaimNotFoundException(claimId));
    }

    /** Amount already committed by authorized or executed settlements. */
    public BigDecimal committedAmount(UUID tenantId, UUID claimId, UUID excludedSettlementId) {
        BigDecimal value = excludedSettlementId == null
                ? jdbc.queryForObject("""
                        SELECT COALESCE(SUM(amount), 0)::numeric(19,4)
                          FROM claim_settlement
                         WHERE tenant_id = ? AND claim_id = ? AND status IN ('AUTHORIZED', 'EXECUTED')
                        """, BigDecimal.class, tenantId, claimId)
                : jdbc.queryForObject("""
                        SELECT COALESCE(SUM(amount), 0)::numeric(19,4)
                          FROM claim_settlement
                         WHERE tenant_id = ? AND claim_id = ? AND status IN ('AUTHORIZED', 'EXECUTED')
                           AND id <> ?
                        """, BigDecimal.class, tenantId, claimId, excludedSettlementId);
        return value == null ? new BigDecimal("0.0000") : value;
    }

    /** Paid loss: settlements that have been executed. */
    public BigDecimal paidLoss(UUID tenantId, UUID claimId) {
        BigDecimal value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0)::numeric(19,4)
                  FROM claim_settlement
                 WHERE tenant_id = ? AND claim_id = ? AND status = 'EXECUTED'
                """, BigDecimal.class, tenantId, claimId);
        return value == null ? new BigDecimal("0.0000") : value;
    }
}
