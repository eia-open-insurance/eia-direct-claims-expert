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

    private static final String INSERT_SQL_TEMPLATE = """
            INSERT INTO claim_command_idempotency
                (tenant_id, idempotency_key, command_type, request_hash, response_status,
                 response_body, created_at)
            VALUES (?, ?, ?, ?, ?, %s, ?)
            """;

    private final JdbcTemplate jdbc;
    private final String insertSql;

    @Autowired
    public ClaimCommandIdempotencyRepository(JdbcTemplate jdbc) {
        this(jdbc, detectJsonParameter(jdbc));
    }

    ClaimCommandIdempotencyRepository(JdbcTemplate jdbc, String jsonParameter) {
        this.jdbc = jdbc;
        this.insertSql = INSERT_SQL_TEMPLATE.formatted(jsonParameter);
    }

    public Optional<ClaimCommandIdempotency> find(UUID tenantId, String idempotencyKey,
                                                  String commandType) {
        return jdbc.query(FIND_SQL, MAPPER, tenantId, idempotencyKey, commandType)
                .stream().findFirst();
    }

    public void insert(UUID tenantId,
                       String idempotencyKey,
                       String commandType,
                       String requestHash,
                       int responseStatus,
                       String responseBody,
                       Instant now) {
        jdbc.update(insertSql, tenantId, idempotencyKey, commandType, requestHash,
                responseStatus, responseBody, Timestamp.from(now));
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
