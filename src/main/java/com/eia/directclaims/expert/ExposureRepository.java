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
public class ExposureRepository {
    private static final String COLUMNS = """
            id, tenant_id, claim_id, claimant_party_id, exposure_type, product_name, substance_name,
            site_name, employer_party_id, job_title, jurisdiction, exposure_from, exposure_to, intensity,
            version, created_at, updated_at
            """;

    private static final RowMapper<ClaimExposure> MAPPER = (rs, rowNum) -> new ClaimExposure(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("claim_id", UUID.class),
            rs.getObject("claimant_party_id", UUID.class),
            rs.getString("exposure_type"),
            rs.getString("product_name"),
            rs.getString("substance_name"),
            rs.getString("site_name"),
            rs.getObject("employer_party_id", UUID.class),
            rs.getString("job_title"),
            rs.getString("jurisdiction"),
            rs.getObject("exposure_from", LocalDate.class),
            rs.getObject("exposure_to", LocalDate.class),
            rs.getString("intensity"),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public ExposureRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ClaimExposure> findAll(UUID tenantId, UUID claimId) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim_exposure
                     WHERE tenant_id = ? AND claim_id = ?
                     ORDER BY exposure_from ASC, id ASC
                    """, MAPPER, tenantId, claimId);
    }

    public Optional<ClaimExposure> findById(UUID tenantId, UUID claimId, UUID exposureId) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim_exposure
                     WHERE tenant_id = ? AND claim_id = ? AND id = ?
                    """, MAPPER, tenantId, claimId, exposureId).stream().findFirst();
    }

    public long count(UUID tenantId, UUID claimId) {
        Long value = jdbc.queryForObject("""
                SELECT COUNT(*) FROM claim_exposure WHERE tenant_id = ? AND claim_id = ?
                """, Long.class, tenantId, claimId);
        return value == null ? 0L : value;
    }

    public ClaimExposure insert(UUID id,
                                UUID tenantId,
                                UUID claimId,
                                UUID claimantPartyId,
                                String exposureType,
                                String productName,
                                String substanceName,
                                String siteName,
                                UUID employerPartyId,
                                String jobTitle,
                                String jurisdiction,
                                LocalDate exposureFrom,
                                LocalDate exposureTo,
                                String intensity,
                                Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        jdbc.update("""
                INSERT INTO claim_exposure (id, tenant_id, claim_id, claimant_party_id, exposure_type,
                                            product_name, substance_name, site_name, employer_party_id,
                                            job_title, jurisdiction, exposure_from, exposure_to, intensity,
                                            version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                """, id, tenantId, claimId, claimantPartyId, exposureType, productName, substanceName,
                siteName, employerPartyId, jobTitle, jurisdiction, exposureFrom, exposureTo, intensity,
                timestamp, timestamp);
        return findById(tenantId, claimId, id).orElseThrow();
    }

    public ClaimExposure update(UUID tenantId,
                                UUID claimId,
                                UUID exposureId,
                                UUID claimantPartyId,
                                String exposureType,
                                String productName,
                                String substanceName,
                                String siteName,
                                UUID employerPartyId,
                                String jobTitle,
                                String jurisdiction,
                                LocalDate exposureFrom,
                                LocalDate exposureTo,
                                String intensity,
                                int expectedVersion,
                                Instant now) {
        int changed = jdbc.update("""
                UPDATE claim_exposure
                   SET claimant_party_id = ?, exposure_type = ?, product_name = ?, substance_name = ?,
                       site_name = ?, employer_party_id = ?, job_title = ?, jurisdiction = ?,
                       exposure_from = ?, exposure_to = ?, intensity = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND claim_id = ? AND id = ? AND version = ?
                """, claimantPartyId, exposureType, productName, substanceName, siteName, employerPartyId,
                jobTitle, jurisdiction, exposureFrom, exposureTo, intensity, Timestamp.from(now),
                tenantId, claimId, exposureId, expectedVersion);
        if (changed == 0) {
            throw new ClaimConflictException(
                    "Exposure " + exposureId + " was modified concurrently or version "
                            + expectedVersion + " is stale");
        }
        return findById(tenantId, claimId, exposureId).orElseThrow(() -> new ClaimNotFoundException(claimId));
    }
}
