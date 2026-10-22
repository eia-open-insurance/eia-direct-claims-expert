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
public class LitigationRepository {
    private static final String COLUMNS = """
            id, tenant_id, claim_id, matter_type, jurisdiction, counsel_party_id, filing_date,
            trial_date, status, case_number, damages_sought, damages_currency, version,
            created_at, updated_at
            """;

    private static final RowMapper<LitigationMatter> MAPPER = (rs, rowNum) -> new LitigationMatter(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("claim_id", UUID.class),
            rs.getString("matter_type"),
            rs.getString("jurisdiction"),
            rs.getObject("counsel_party_id", UUID.class),
            rs.getObject("filing_date", LocalDate.class),
            rs.getObject("trial_date", LocalDate.class),
            rs.getString("status"),
            rs.getString("case_number"),
            rs.getBigDecimal("damages_sought"),
            rs.getString("damages_currency"),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public LitigationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<LitigationMatter> findAll(UUID tenantId, UUID claimId) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim_litigation
                     WHERE tenant_id = ? AND claim_id = ?
                     ORDER BY filing_date DESC, id ASC
                    """, MAPPER, tenantId, claimId);
    }

    public Optional<LitigationMatter> findById(UUID tenantId, UUID claimId, UUID litigationId) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim_litigation
                     WHERE tenant_id = ? AND claim_id = ? AND id = ?
                    """, MAPPER, tenantId, claimId, litigationId).stream().findFirst();
    }

    public LitigationMatter insert(UUID id,
                                   UUID tenantId,
                                   UUID claimId,
                                   String matterType,
                                   String jurisdiction,
                                   UUID counselPartyId,
                                   LocalDate filingDate,
                                   LocalDate trialDate,
                                   String status,
                                   String caseNumber,
                                   BigDecimal damagesSought,
                                   String damagesCurrency,
                                   Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        jdbc.update("""
                INSERT INTO claim_litigation (id, tenant_id, claim_id, matter_type, jurisdiction,
                                              counsel_party_id, filing_date, trial_date, status,
                                              case_number, damages_sought, damages_currency, version,
                                              created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                """, id, tenantId, claimId, matterType, jurisdiction, counselPartyId, filingDate,
                trialDate, status, caseNumber, damagesSought, damagesCurrency, timestamp, timestamp);
        return findById(tenantId, claimId, id).orElseThrow();
    }

    public LitigationMatter update(UUID tenantId,
                                   UUID claimId,
                                   UUID litigationId,
                                   String matterType,
                                   String jurisdiction,
                                   UUID counselPartyId,
                                   LocalDate filingDate,
                                   LocalDate trialDate,
                                   String status,
                                   String caseNumber,
                                   BigDecimal damagesSought,
                                   String damagesCurrency,
                                   int expectedVersion,
                                   Instant now) {
        int changed = jdbc.update("""
                UPDATE claim_litigation
                   SET matter_type = ?, jurisdiction = ?, counsel_party_id = ?, filing_date = ?,
                       trial_date = ?, status = ?, case_number = ?, damages_sought = ?,
                       damages_currency = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND claim_id = ? AND id = ? AND version = ?
                """, matterType, jurisdiction, counselPartyId, filingDate, trialDate, status, caseNumber,
                damagesSought, damagesCurrency, Timestamp.from(now), tenantId, claimId, litigationId,
                expectedVersion);
        if (changed == 0) {
            throw new ClaimConflictException("Litigation matter " + litigationId
                    + " was modified concurrently or version " + expectedVersion + " is stale");
        }
        return findById(tenantId, claimId, litigationId).orElseThrow(() -> new ClaimNotFoundException(claimId));
    }
}
