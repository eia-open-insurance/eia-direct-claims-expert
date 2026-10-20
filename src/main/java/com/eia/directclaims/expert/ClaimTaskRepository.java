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
public class ClaimTaskRepository {
    private static final String COLUMNS = """
            id, tenant_id, claim_id, task_number, title, description, due_date, priority, status,
            assigned_to, version, created_at, updated_at, completed_at
            """;

    private static final RowMapper<ClaimTask> MAPPER = (rs, rowNum) -> new ClaimTask(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("claim_id", UUID.class),
            rs.getString("task_number"),
            rs.getString("title"),
            rs.getString("description"),
            rs.getObject("due_date", LocalDate.class),
            rs.getString("priority"),
            rs.getString("status"),
            rs.getString("assigned_to"),
            rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public ClaimTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long nextTaskSequence() {
        Long value = jdbc.queryForObject("SELECT nextval('claim_task_number_seq')", Long.class);
        return value == null ? 1L : value;
    }

    /**
     * Open tasks first, ordered by due date, then cancelled tasks, then completed
     * tasks newest-completed first. Every read is tenant- and claim-scoped.
     */
    public List<ClaimTask> findAll(UUID tenantId, UUID claimId) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim_task
                     WHERE tenant_id = ? AND claim_id = ?
                     ORDER BY CASE status
                                  WHEN 'OPEN' THEN 0
                                  WHEN 'CANCELLED' THEN 1
                                  WHEN 'COMPLETE' THEN 2
                                  ELSE 3
                              END,
                              CASE WHEN status = 'OPEN' THEN due_date END ASC,
                              CASE WHEN status = 'COMPLETE' THEN completed_at END DESC,
                              created_at DESC,
                              task_number DESC
                    """, MAPPER, tenantId, claimId);
    }

    public Optional<ClaimTask> findById(UUID tenantId, UUID claimId, UUID taskId) {
        return jdbc.query("SELECT " + COLUMNS + """
                      FROM claim_task
                     WHERE tenant_id = ? AND claim_id = ? AND id = ?
                    """, MAPPER, tenantId, claimId, taskId).stream().findFirst();
    }

    public ClaimTask insert(UUID id,
                            UUID tenantId,
                            UUID claimId,
                            String taskNumber,
                            String title,
                            String description,
                            LocalDate dueDate,
                            String priority,
                            String assignedTo,
                            Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        jdbc.update("""
                INSERT INTO claim_task (id, tenant_id, claim_id, task_number, title, description,
                                        due_date, priority, status, assigned_to, version,
                                        created_at, updated_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, 1, ?, ?, NULL)
                """, id, tenantId, claimId, taskNumber, title, description, dueDate, priority,
                assignedTo, timestamp, timestamp);
        return findById(tenantId, claimId, id).orElseThrow();
    }

    /**
     * Optimistic status change. {@code completed_at} is set only for COMPLETE and
     * cleared when a task is reopened. A stale version returns no row and is
     * translated to 409 by the service.
     */
    public Optional<ClaimTask> updateStatus(UUID tenantId,
                                            UUID claimId,
                                            UUID taskId,
                                            String status,
                                            long expectedVersion,
                                            Instant now) {
        Timestamp completedAt = "COMPLETE".equals(status) ? Timestamp.from(now) : null;
        int changed = jdbc.update("""
                UPDATE claim_task
                   SET status = ?, version = version + 1, updated_at = ?, completed_at = ?
                 WHERE tenant_id = ? AND claim_id = ? AND id = ? AND version = ?
                """, status, Timestamp.from(now), completedAt, tenantId, claimId, taskId,
                expectedVersion);
        if (changed == 0) {
            return Optional.empty();
        }
        return findById(tenantId, claimId, taskId);
    }
}
