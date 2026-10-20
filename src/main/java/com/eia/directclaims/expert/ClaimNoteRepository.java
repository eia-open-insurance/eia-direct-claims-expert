package com.eia.directclaims.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ClaimNoteRepository {
    private static final String COLUMNS = """
            id, tenant_id, claim_id, note_number, note_type, body, author, created_at
            """;

    private static final RowMapper<ClaimNote> MAPPER = (rs, rowNum) -> new ClaimNote(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("claim_id", UUID.class),
            rs.getString("note_number"),
            rs.getString("note_type"),
            rs.getString("body"),
            rs.getString("author"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public ClaimNoteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long nextNoteSequence() {
        Long value = jdbc.queryForObject("SELECT nextval('claim_note_number_seq')", Long.class);
        return value == null ? 1L : value;
    }

    /** Newest first; note number is the deterministic tiebreaker. */
    public List<ClaimNote> findAll(UUID tenantId, UUID claimId) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim_note
                     WHERE tenant_id = ? AND claim_id = ?
                     ORDER BY created_at DESC, note_number DESC
                    """, MAPPER, tenantId, claimId);
    }

    public Optional<ClaimNote> findById(UUID tenantId, UUID claimId, UUID noteId) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim_note
                     WHERE tenant_id = ? AND claim_id = ? AND id = ?
                    """, MAPPER, tenantId, claimId, noteId).stream().findFirst();
    }

    public ClaimNote insert(UUID id,
                            UUID tenantId,
                            UUID claimId,
                            String noteNumber,
                            String noteType,
                            String body,
                            String author,
                            Instant now) {
        jdbc.update("""
                INSERT INTO claim_note (id, tenant_id, claim_id, note_number, note_type, body,
                                        author, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, claimId, noteNumber, noteType, body, author, Timestamp.from(now));
        return findById(tenantId, claimId, id).orElseThrow();
    }
}
