package com.eia.directclaims.expert;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for the task and note create idempotency records.
 *
 * <p>The composite primary key is the concurrency arbiter. A command first
 * <em>reserves</em> its key with {@code INSERT ... ON CONFLICT DO NOTHING}; the
 * row starts without a response and is <em>completed</em> with the original 201
 * body after the child row and its outbox event have been written. A
 * simultaneous same-key command loses the reservation instead of raising a
 * unique-constraint error, reads the winner's committed row, and replays it.</p>
 *
 * <p>Production stores {@code response_body} as {@code jsonb}; H2 component
 * tests use a text column, so the bound JSON parameter needs a cast on
 * PostgreSQL only (the same dialect detection the platform outbox repository
 * uses).</p>
 */
@Repository
public class ClaimCommandIdempotencyRepository {
    static final String TASK_CREATE = "TASK_CREATE";
    static final String NOTE_CREATE = "NOTE_CREATE";

    private static final String COLUMNS = """
            tenant_id, idempotency_key, command_type, request_hash, response_status,
            response_body, created_at
            """;

    private static final RowMapper<ClaimCommandIdempotency> MAPPER = (rs, rowNum) ->
            new ClaimCommandIdempotency(
                    rs.getObject("tenant_id", UUID.class),
                    rs.getString("idempotency_key"),
                    rs.getString("command_type"),
                    rs.getString("request_hash"),
                    rs.getInt("response_status"),
                    rs.getString("response_body"),
                    rs.getTimestamp("created_at").toInstant());

    private static final String FIND_SQL = "SELECT " + COLUMNS + """
              FROM claim_command_idempotency
             WHERE tenant_id = ? AND idempotency_key = ? AND command_type = ?
            """;

    /**
     * The conflicting insert is skipped rather than failing, so a concurrent
     * same-key request returns 0 here and can replay the winner instead of
     * surfacing a database constraint error.
     */
    private static final String RESERVE_SQL = """
            INSERT INTO claim_command_idempotency
                (tenant_id, idempotency_key, command_type, request_hash, created_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;

    private static final String COMPLETE_SQL_TEMPLATE = """
            UPDATE claim_command_idempotency
               SET response_status = ?, response_body = %s
             WHERE tenant_id = ? AND idempotency_key = ? AND command_type = ?
            """;

    private final JdbcTemplate jdbc;
    private final String completeSql;

    @Autowired
    public ClaimCommandIdempotencyRepository(JdbcTemplate jdbc) {
        this(jdbc, detectJsonParameter(jdbc));
    }

    ClaimCommandIdempotencyRepository(JdbcTemplate jdbc, String jsonParameter) {
        this.jdbc = jdbc;
        this.completeSql = COMPLETE_SQL_TEMPLATE.formatted(jsonParameter);
    }

    public Optional<ClaimCommandIdempotency> find(UUID tenantId, String idempotencyKey,
                                                  String commandType) {
        return jdbc.query(FIND_SQL, MAPPER, tenantId, idempotencyKey, commandType)
                .stream().findFirst();
    }

    /**
     * Reserves the key in the caller's transaction. While another transaction
     * owns the key the insert waits for it to finish: if it commits this
     * returns {@code false} and the caller can replay the winner, and if it
     * rolls back this caller takes the reservation and returns {@code true}.
     * A unique-constraint error is never raised.
     *
     * @return true when this caller inserted the row; false when the key is
     *         already owned by another committed command
     */
    public boolean reserve(UUID tenantId,
                           String idempotencyKey,
                           String commandType,
                           String requestHash,
                           Instant now) {
        return jdbc.update(RESERVE_SQL, tenantId, idempotencyKey, commandType, requestHash,
                Timestamp.from(now)) == 1;
    }

    /**
     * Stores the original 201 status and body after the command action
     * succeeded, in the same transaction as the reservation.
     *
     * @throws IllegalStateException when the reservation row is missing, which
     *         means the reservation and the completion disagree
     */
    public void complete(UUID tenantId,
                         String idempotencyKey,
                         String commandType,
                         int responseStatus,
                         String responseBody) {
        int updated = jdbc.update(completeSql, responseStatus, responseBody, tenantId,
                idempotencyKey, commandType);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Idempotency reservation was not found while storing the response");
        }
    }

    /**
     * PostgreSQL needs an explicit JSON cast for a bound varchar. H2 (used by
     * component tests) accepts the plain parameter into its text columns.
     */
    private static String detectJsonParameter(JdbcTemplate jdbc) {
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null) {
            return "CAST(? AS JSON)";
        }
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            return "H2".equalsIgnoreCase(product) ? "?" : "CAST(? AS JSON)";
        } catch (SQLException ex) {
            return "CAST(? AS JSON)";
        }
    }
}
