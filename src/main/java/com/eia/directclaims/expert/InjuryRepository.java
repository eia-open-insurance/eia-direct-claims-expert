package com.eia.directclaims.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InjuryRepository {
    private static final String COLUMNS = """
            id, tenant_id, claim_id, claimant_party_id, injury_type, body_part, diagnosis_codes,
            description, severity, diagnosis_date, version, created_at, updated_at
            """;

    private static final RowMapper<ClaimInjury> MAPPER = (rs, rowNum) -> new ClaimInjury(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("claim_id", UUID.class),
            rs.getObject("claimant_party_id", UUID.class),
            rs.getString("injury_type"),
            rs.getString("body_part"),
            SqlTextArray.read(rs, "diagnosis_codes"),
            rs.getString("description"),
            rs.getString("severity"),
            rs.getObject("diagnosis_date", LocalDate.class),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public InjuryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ClaimInjury> findAll(UUID tenantId, UUID claimId) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim_injury
                     WHERE tenant_id = ? AND claim_id = ?
                     ORDER BY created_at ASC, id ASC
                    """, MAPPER, tenantId, claimId);
    }

    public Optional<ClaimInjury> findById(UUID tenantId, UUID claimId, UUID injuryId) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim_injury
                     WHERE tenant_id = ? AND claim_id = ? AND id = ?
                    """, MAPPER, tenantId, claimId, injuryId).stream().findFirst();
    }

    public long count(UUID tenantId, UUID claimId) {
        Long value = jdbc.queryForObject("""
                SELECT COUNT(*) FROM claim_injury WHERE tenant_id = ? AND claim_id = ?
                """, Long.class, tenantId, claimId);
        return value == null ? 0L : value;
    }

    public ClaimInjury insert(UUID id,
                              UUID tenantId,
                              UUID claimId,
                              UUID claimantPartyId,
                              String injuryType,
                              String bodyPart,
                              List<String> diagnosisCodes,
                              String description,
                              String severity,
                              LocalDate diagnosisDate,
                              Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        jdbc.update("""
                INSERT INTO claim_injury (id, tenant_id, claim_id, claimant_party_id, injury_type, body_part,
                                          diagnosis_codes, description, severity, diagnosis_date, version,
                                          created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?, 1, ?, ?)
                """, id, tenantId, claimId, claimantPartyId, injuryType, bodyPart,
                SqlTextArray.literal(diagnosisCodes), description, severity, diagnosisDate, timestamp, timestamp);
        return findById(tenantId, claimId, id).orElseThrow();
    }

    public ClaimInjury update(UUID tenantId,
                              UUID claimId,
                              UUID injuryId,
                              UUID claimantPartyId,
                              String injuryType,
                              String bodyPart,
                              List<String> diagnosisCodes,
                              String description,
                              String severity,
                              LocalDate diagnosisDate,
                              int expectedVersion,
                              Instant now) {
        int changed = jdbc.update("""
                UPDATE claim_injury
                   SET claimant_party_id = ?, injury_type = ?, body_part = ?, diagnosis_codes = ?::text[],
                       description = ?, severity = ?, diagnosis_date = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND claim_id = ? AND id = ? AND version = ?
                """, claimantPartyId, injuryType, bodyPart, SqlTextArray.literal(diagnosisCodes),
                description, severity, diagnosisDate, Timestamp.from(now), tenantId, claimId, injuryId,
                expectedVersion);
        if (changed == 0) {
            throw new ClaimConflictException(
                    "Injury " + injuryId + " was modified concurrently or version " + expectedVersion + " is stale");
        }
        return findById(tenantId, claimId, injuryId).orElseThrow(() -> new ClaimNotFoundException(claimId));
    }
}
