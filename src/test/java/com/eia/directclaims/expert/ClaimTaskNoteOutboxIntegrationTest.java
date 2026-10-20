package com.eia.directclaims.expert;

import com.eia.platform.libs.events.EventJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end task and note persistence, ordering, tenant isolation, outbox
 * payloads, and transactional rollback against the H2 test schema.
 */
@SpringBootTest
@ActiveProfiles("test")
class ClaimTaskNoteOutboxIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID CLAIMANT_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");

    @Autowired
    private ClaimService claimService;
    @Autowired
    private ClaimTaskService taskService;
    @Autowired
    private ClaimNoteService noteService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM claim_note");
        jdbc.update("DELETE FROM claim_task");
        jdbc.update("DELETE FROM claim");
    }

    // -------------------------------------------------------------------- tasks
    @Test
    void taskLifecycleWritesCreatedAndCompletedEvents() {
        Claim claim = createClaim();
        ClaimTask task = taskService.create(TENANT, claim.id(), new CreateTaskRequest(
                "Review medical records", "Order records and index the file",
                LocalDate.of(2026, 4, 15), "HIGH", "adjuster@example.com"));

        assertThat(task.status()).isEqualTo("OPEN");
        assertThat(task.version()).isEqualTo(1);
        assertThat(task.taskNumber()).startsWith("TSK-");
        assertThat(task.completedAt()).isNull();
        assertThat(claimVersion(claim.id())).isEqualTo(1);

        Map<String, Object> createdEvent = lastEvent(claim.id());
        assertThat(createdEvent.get("event_type")).isEqualTo("direct.claim.task-created");
        assertThat(createdEvent.get("aggregate_type")).isEqualTo("Claim");
        assertThat(aggregateVersion(createdEvent)).isEqualTo(2L);
        Map<String, Object> createdPayload = payload(createdEvent);
        assertThat(createdPayload.get("claimId")).isEqualTo(claim.id().toString());
        assertThat(createdPayload.get("taskId")).isEqualTo(task.id().toString());
        assertThat(createdPayload.get("taskNumber")).isEqualTo(task.taskNumber());
        assertThat(createdPayload.get("title")).isEqualTo("Review medical records");
        assertThat(createdPayload.get("status")).isEqualTo("OPEN");
        assertThat(createdPayload.get("claimStatus")).isEqualTo("REGISTERED");
        assertThat(createdPayload.get("priority")).isEqualTo("HIGH");
        assertThat(createdPayload.get("dueDate")).isEqualTo("2026-04-15");
        assertThat(createdPayload.get("assignedTo")).isEqualTo("adjuster@example.com");
        assertThat(createdPayload.get("version")).isEqualTo(1);
        assertThat(createdPayload.get("taskVersion")).isEqualTo(1);

        ClaimTask completed = taskService.changeStatus(TENANT, claim.id(), task.id(),
                new TaskStatusChangeRequest("COMPLETE", task.version()));

        assertThat(completed.status()).isEqualTo("COMPLETE");
        assertThat(completed.version()).isEqualTo(2);
        assertThat(completed.completedAt()).isNotNull();
        assertThat(claimVersion(claim.id())).isEqualTo(2);

        Map<String, Object> completedEvent = lastEvent(claim.id());
        assertThat(completedEvent.get("event_type")).isEqualTo("direct.claim.task-completed");
        assertThat(aggregateVersion(completedEvent)).isEqualTo(3L);
        Map<String, Object> completedPayload = payload(completedEvent);
        assertThat(completedPayload.get("taskId")).isEqualTo(task.id().toString());
        assertThat(completedPayload.get("status")).isEqualTo("COMPLETE");
        assertThat(completedPayload.get("claimStatus")).isEqualTo("REGISTERED");
        assertThat(completedPayload.get("from")).isEqualTo("OPEN");
        assertThat(completedPayload.get("to")).isEqualTo("COMPLETE");
        assertThat(completedPayload.get("taskVersion")).isEqualTo(2);
        assertThat(completedPayload.get("completedAt")).isNotNull();
    }

    @Test
    void taskListPutsOpenTasksFirstByDueDateThenCompleted() {
        Claim claim = createClaim();
        ClaimTask late = createTask(claim, "Late task", LocalDate.of(2026, 5, 1), "NORMAL");
        ClaimTask early = createTask(claim, "Early task", LocalDate.of(2026, 4, 1), "HIGH");
        ClaimTask done = createTask(claim, "Done task", LocalDate.of(2026, 3, 1), "LOW");

        taskService.changeStatus(TENANT, claim.id(), done.id(),
                new TaskStatusChangeRequest("COMPLETE", done.version()));

        List<ClaimTask> tasks = taskService.list(TENANT, claim.id());

        assertThat(tasks).extracting(ClaimTask::id)
                .containsExactly(early.id(), late.id(), done.id());
        assertThat(tasks).extracting(ClaimTask::status)
                .containsExactly("OPEN", "OPEN", "COMPLETE");
        assertThat(tasks).extracting(ClaimTask::dueDate)
                .containsExactly(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 5, 1),
                        LocalDate.of(2026, 3, 1));
    }

    @Test
    void staleTaskVersionIs409AndWritesNoEvent() {
        Claim claim = createClaim();
        ClaimTask task = createTask(claim, "Review medical records",
                LocalDate.of(2026, 4, 15), "HIGH");
        taskService.changeStatus(TENANT, claim.id(), task.id(),
                new TaskStatusChangeRequest("COMPLETE", task.version()));

        assertThatThrownBy(() -> taskService.changeStatus(TENANT, claim.id(), task.id(),
                new TaskStatusChangeRequest("CANCELLED", task.version())))
                .isInstanceOf(ClaimConflictException.class)
                .hasMessageContaining("does not match current version");

        assertThat(taskStatus(task.id())).isEqualTo("COMPLETE");
        assertThat(taskVersion(task.id())).isEqualTo(2L);
        assertThat(countEvents(claim.id(), "direct.claim.task-completed")).isEqualTo(1);
        assertThat(countEvents(claim.id(), "direct.claim.task-updated")).isZero();
    }

    @Test
    void tasksAndNotesAreTenantAndClaimIsolated() {
        Claim claim = createClaim();
        Claim otherClaim = createClaim();
        ClaimTask task = createTask(claim, "Review medical records",
                LocalDate.of(2026, 4, 15), "HIGH");
        UUID otherTenant = UUID.fromString("99999999-9999-7999-8999-999999999999");

        assertThatThrownBy(() -> taskService.list(otherTenant, claim.id()))
                .isInstanceOf(ClaimNotFoundException.class);
        assertThatThrownBy(() -> taskService.create(otherTenant, claim.id(), new CreateTaskRequest(
                "Intruder task", null, LocalDate.of(2026, 4, 15), "LOW", null)))
                .isInstanceOf(ClaimNotFoundException.class);
        assertThatThrownBy(() -> taskService.changeStatus(otherTenant, claim.id(), task.id(),
                new TaskStatusChangeRequest("COMPLETE", task.version())))
                .isInstanceOf(ClaimNotFoundException.class);
        assertThatThrownBy(() -> noteService.list(otherTenant, claim.id()))
                .isInstanceOf(ClaimNotFoundException.class);
        assertThatThrownBy(() -> taskService.changeStatus(TENANT, otherClaim.id(), task.id(),
                new TaskStatusChangeRequest("COMPLETE", task.version())))
                .isInstanceOf(RecordNotFoundException.class);
        assertThat(count("claim_task")).isEqualTo(1);
    }

    @Test
    void taskCreateRollbackLeavesNoTaskVersionChangeOrEvent() {
        Claim claim = createClaim();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            taskService.create(TENANT, claim.id(), new CreateTaskRequest(
                    "Review medical records", null, LocalDate.of(2026, 4, 15), "HIGH", null));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("claim_task")).isZero();
        assertThat(claimVersion(claim.id())).isZero();
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(countEvents(claim.id(), "direct.claim.task-created")).isZero();
    }

    // -------------------------------------------------------------------- notes
    @Test
    void notesAppendAndListNewestFirst() {
        Claim claim = createClaim();
        ClaimNote first = noteService.add(TENANT, claim.id(),
                new CreateNoteRequest("DIARY", "Called the claimant", "adjuster@example.com"));
        ClaimNote second = noteService.add(TENANT, claim.id(),
                new CreateNoteRequest("SETTLEMENT", "Authority requested", "manager@example.com"));

        List<ClaimNote> notes = noteService.list(TENANT, claim.id());

        assertThat(notes).extracting(ClaimNote::id)
                .containsExactly(second.id(), first.id());
        assertThat(notes).extracting(ClaimNote::noteType)
                .containsExactly("SETTLEMENT", "DIARY");
        assertThat(notes).extracting(ClaimNote::body)
                .containsExactly("Authority requested", "Called the claimant");
        assertThat(countEvents(claim.id(), "direct.claim.note-added")).isEqualTo(2);
        assertThat(claimVersion(claim.id())).isEqualTo(2);

        Map<String, Object> event = lastEvent(claim.id());
        assertThat(event.get("event_type")).isEqualTo("direct.claim.note-added");
        assertThat(aggregateVersion(event)).isEqualTo(3L);
        Map<String, Object> notePayload = payload(event);
        assertThat(notePayload.get("claimId")).isEqualTo(claim.id().toString());
        assertThat(notePayload.get("noteId")).isEqualTo(second.id().toString());
        assertThat(notePayload.get("noteNumber")).isEqualTo(second.noteNumber());
        assertThat(notePayload.get("noteType")).isEqualTo("SETTLEMENT");
        assertThat(notePayload.get("body")).isEqualTo("Authority requested");
        assertThat(notePayload.get("author")).isEqualTo("manager@example.com");
        assertThat(notePayload.get("version")).isEqualTo(2);
    }

    @Test
    void noteCreateRollbackLeavesNoNoteVersionChangeOrEvent() {
        Claim claim = createClaim();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            noteService.add(TENANT, claim.id(),
                    new CreateNoteRequest("DIARY", "Called the claimant", "adjuster@example.com"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("claim_note")).isZero();
        assertThat(claimVersion(claim.id())).isZero();
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(countEvents(claim.id(), "direct.claim.note-added")).isZero();
    }

    // ------------------------------------------------------------------ helpers
    private Claim createClaim() {
        return claimService.create(new CreateClaimRequest(
                TENANT, POLICY_ID, CLAIMANT_ID,
                LocalDate.of(2026, 3, 14), LocalDate.of(2026, 3, 18),
                "GL", "PROPERTY_DAMAGE", new BigDecimal("0.00"), "USD",
                new BigDecimal("500000.00"), "USD", null));
    }

    private ClaimTask createTask(Claim claim, String title, LocalDate dueDate, String priority) {
        return taskService.create(TENANT, claim.id(),
                new CreateTaskRequest(title, null, dueDate, priority, "adjuster@example.com"));
    }

    private int claimVersion(UUID claimId) {
        return jdbc.queryForObject("SELECT version FROM claim WHERE id = ?", Integer.class, claimId);
    }

    private String taskStatus(UUID taskId) {
        return jdbc.queryForObject("SELECT status FROM claim_task WHERE id = ?", String.class, taskId);
    }

    private long taskVersion(UUID taskId) {
        Long value = jdbc.queryForObject("SELECT version FROM claim_task WHERE id = ?", Long.class,
                taskId);
        return value == null ? 0L : value;
    }

    private List<Map<String, Object>> events(UUID claimId) {
        return jdbc.queryForList("""
                SELECT event_type, aggregate_type, aggregate_version, payload
                  FROM outbox_event
                 WHERE aggregate_id = ?
                 ORDER BY event_sequence
                """, claimId);
    }

    private Map<String, Object> lastEvent(UUID claimId) {
        List<Map<String, Object>> events = events(claimId);
        assertThat(events).isNotEmpty();
        return events.get(events.size() - 1);
    }

    private int countEvents(UUID claimId, String eventType) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = ?
                """, Integer.class, claimId, eventType);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static long aggregateVersion(Map<String, Object> event) {
        return ((Number) event.get("aggregate_version")).longValue();
    }

    private static Map<String, Object> payload(Map<String, Object> event) {
        return EventJson.toMap((String) event.get("payload"));
    }
}
