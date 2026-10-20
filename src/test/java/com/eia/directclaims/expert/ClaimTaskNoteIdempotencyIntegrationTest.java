package com.eia.directclaims.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * End-to-end Idempotency-Key behaviour for the task and note create commands
 * against the H2 test schema: first create, replay, conflict, missing key,
 * tenant/command scoping, and rollback.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClaimTaskNoteIdempotencyIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID POLICY_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID CLAIMANT_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");

    @Autowired
    private MockMvc mvc;
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
    @Autowired
    private ObjectMapper objectMapper;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM claim_command_idempotency");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM claim_note");
        jdbc.update("DELETE FROM claim_task");
        jdbc.update("DELETE FROM claim");
    }

    // -------------------------------------------------------------------- tasks
    @Test
    void taskCreateReplaysTheOriginal201WithoutDuplicateRowOrEvent() throws Exception {
        Claim claim = createClaim(TENANT);

        MvcResult first = postTask(TENANT, claim.id(), "task-key-1", "Review medical records");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(first.getResponse().getHeader("Idempotent-Replay")).isEqualTo("false");
        JsonNode firstBody = json(first);

        MvcResult replay = postTask(TENANT, claim.id(), "task-key-1", "Review medical records");
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
        assertThat(json(replay)).isEqualTo(firstBody);

        assertThat(count("claim_task")).isEqualTo(1);
        assertThat(count("claim_command_idempotency")).isEqualTo(1);
        assertThat(countEvents(claim.id(), ClaimEvents.TASK_CREATED)).isEqualTo(1);
        assertThat(claimVersion(claim.id())).isEqualTo(1);
    }

    @Test
    void taskCreateStoresTheOriginalResponseStatusAndBody() throws Exception {
        Claim claim = createClaim(TENANT);

        postTask(TENANT, claim.id(), "task-key-1", "Review medical records");

        var record = jdbc.queryForMap("""
                SELECT command_type, response_status, response_body, request_hash
                  FROM claim_command_idempotency
                 WHERE tenant_id = ? AND idempotency_key = ?
                """, TENANT, "task-key-1");
        assertThat(record.get("command_type")).isEqualTo("TASK_CREATE");
        assertThat(((Number) record.get("response_status")).intValue()).isEqualTo(201);
        assertThat(objectMapper.readTree((String) record.get("response_body"))
                .get("taskNumber").asText()).startsWith("TSK-");
        assertThat((String) record.get("request_hash")).hasSize(64);
    }

    @Test
    void reusingATaskKeyWithADifferentBodyIs409AndWritesNothing() throws Exception {
        Claim claim = createClaim(TENANT);
        postTask(TENANT, claim.id(), "task-key-1", "Review medical records");

        MvcResult conflict = postTask(TENANT, claim.id(), "task-key-1", "A different task");
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(conflict.getResponse().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode problem = json(conflict);
        assertThat(problem.get("code").asText()).isEqualTo("EIA-DIR-4091");
        assertThat(problem.get("category").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

        assertThat(count("claim_task")).isEqualTo(1);
        assertThat(count("claim_command_idempotency")).isEqualTo(1);
        assertThat(countEvents(claim.id(), ClaimEvents.TASK_CREATED)).isEqualTo(1);
        assertThat(claimVersion(claim.id())).isEqualTo(1);
    }

    @Test
    void missingTaskKeyCreatesTwice() throws Exception {
        Claim claim = createClaim(TENANT);

        JsonNode first = json(postTask(TENANT, claim.id(), null, "Review medical records"));
        JsonNode second = json(postTask(TENANT, claim.id(), null, "Review medical records"));

        assertThat(second.get("id").asText()).isNotEqualTo(first.get("id").asText());
        assertThat(count("claim_task")).isEqualTo(2);
        assertThat(count("claim_command_idempotency")).isZero();
        assertThat(countEvents(claim.id(), ClaimEvents.TASK_CREATED)).isEqualTo(2);
    }

    @Test
    void taskKeyIsScopedByTenant() throws Exception {
        Claim directClaim = createClaim(TENANT);
        Claim otherClaim = createClaim(OTHER_TENANT);

        JsonNode first = json(postTask(TENANT, directClaim.id(), "shared-key", "Review records"));
        JsonNode second = json(postTask(OTHER_TENANT, otherClaim.id(), "shared-key", "Review records"));

        assertThat(second.get("id").asText()).isNotEqualTo(first.get("id").asText());
        assertThat(count("claim_task")).isEqualTo(2);
        assertThat(count("claim_command_idempotency")).isEqualTo(2);
    }

    @Test
    void taskKeyIsScopedByCommandSoTheSameKeyCanCreateANote() throws Exception {
        Claim claim = createClaim(TENANT);

        MvcResult task = postTask(TENANT, claim.id(), "shared-key", "Review records");
        MvcResult note = postNote(TENANT, claim.id(), "shared-key", "Called the claimant");

        assertThat(task.getResponse().getStatus()).isEqualTo(201);
        assertThat(note.getResponse().getStatus()).isEqualTo(201);
        assertThat(count("claim_task")).isEqualTo(1);
        assertThat(count("claim_note")).isEqualTo(1);
        assertThat(count("claim_command_idempotency")).isEqualTo(2);
    }

    @Test
    void anInvalidTaskKeyIsRejectedBeforeAnythingIsWritten() throws Exception {
        Claim claim = createClaim(TENANT);

        MvcResult bad = postTask(TENANT, claim.id(), "k".repeat(65), "Review records");
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(bad).get("code").asText()).isEqualTo("VALIDATION_ERROR");

        MvcResult spaced = postTask(TENANT, claim.id(), "task key", "Review records");
        assertThat(spaced.getResponse().getStatus()).isEqualTo(400);

        assertThat(count("claim_task")).isZero();
        assertThat(count("claim_command_idempotency")).isZero();
    }

    // -------------------------------------------------------------------- notes
    @Test
    void noteCreateReplaysTheOriginal201WithoutDuplicateRowOrEvent() throws Exception {
        Claim claim = createClaim(TENANT);

        MvcResult first = postNote(TENANT, claim.id(), "note-key-1", "Called the claimant");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(first.getResponse().getHeader("Idempotent-Replay")).isEqualTo("false");
        JsonNode firstBody = json(first);

        MvcResult replay = postNote(TENANT, claim.id(), "note-key-1", "Called the claimant");
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
        assertThat(json(replay)).isEqualTo(firstBody);

        assertThat(count("claim_note")).isEqualTo(1);
        assertThat(count("claim_command_idempotency")).isEqualTo(1);
        assertThat(countEvents(claim.id(), ClaimEvents.NOTE_ADDED)).isEqualTo(1);
        assertThat(claimVersion(claim.id())).isEqualTo(1);
    }

    @Test
    void reusingANoteKeyWithADifferentBodyIs409AndWritesNothing() throws Exception {
        Claim claim = createClaim(TENANT);
        postNote(TENANT, claim.id(), "note-key-1", "Called the claimant");

        MvcResult conflict = postNote(TENANT, claim.id(), "note-key-1", "A different note");
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(conflict).get("code").asText()).isEqualTo("EIA-DIR-4091");
        assertThat(json(conflict).get("category").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

        assertThat(count("claim_note")).isEqualTo(1);
        assertThat(countEvents(claim.id(), ClaimEvents.NOTE_ADDED)).isEqualTo(1);
    }

    @Test
    void missingNoteKeyCreatesTwice() throws Exception {
        Claim claim = createClaim(TENANT);

        JsonNode first = json(postNote(TENANT, claim.id(), null, "Called the claimant"));
        JsonNode second = json(postNote(TENANT, claim.id(), null, "Called the claimant"));

        assertThat(second.get("id").asText()).isNotEqualTo(first.get("id").asText());
        assertThat(count("claim_note")).isEqualTo(2);
        assertThat(count("claim_command_idempotency")).isZero();
        assertThat(countEvents(claim.id(), ClaimEvents.NOTE_ADDED)).isEqualTo(2);
    }

    // ---------------------------------------------------------------- rollback
    @Test
    void taskRollbackLeavesNoTaskNoEventAndNoStoredKey() {
        Claim claim = createClaim(TENANT);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            taskService.create(TENANT, claim.id(), new CreateTaskRequest(
                    "Review medical records", null, LocalDate.of(2026, 4, 15), "HIGH", null),
                    "rollback-key");
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("force rollback");

        assertThat(count("claim_task")).isZero();
        assertThat(count("claim_command_idempotency")).isZero();
        assertThat(countEvents(claim.id(), ClaimEvents.TASK_CREATED)).isZero();
        assertThat(claimVersion(claim.id())).isZero();

        ClaimCommandResult<ClaimTask> retried = taskService.create(TENANT, claim.id(),
                new CreateTaskRequest("Review medical records", null, LocalDate.of(2026, 4, 15),
                        "HIGH", null), "rollback-key");

        assertThat(retried.replayed()).isFalse();
        assertThat(retried.idempotent()).isTrue();
        assertThat(count("claim_task")).isEqualTo(1);
        assertThat(count("claim_command_idempotency")).isEqualTo(1);
    }

    @Test
    void noteRollbackLeavesNoNoteNoEventAndNoStoredKey() {
        Claim claim = createClaim(TENANT);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            noteService.add(TENANT, claim.id(),
                    new CreateNoteRequest("DIARY", "Called the claimant", "adjuster@example.com"),
                    "rollback-note-key");
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("force rollback");

        assertThat(count("claim_note")).isZero();
        assertThat(count("claim_command_idempotency")).isZero();
        assertThat(countEvents(claim.id(), ClaimEvents.NOTE_ADDED)).isZero();
        assertThat(claimVersion(claim.id())).isZero();
    }

    // ------------------------------------------------------------------ helpers
    private MvcResult postTask(UUID tenantId, UUID claimId, String idempotencyKey, String title)
            throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/claims/{claimId}/tasks", claimId)
                .header("X-EIA-Tenant", tenantId)
                .header("X-EIA-Book", "DIRECT")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "title": "%s",
                          "description": "Order records and index the file",
                          "dueDate": "2026-04-15",
                          "priority": "HIGH",
                          "assignedTo": "adjuster@example.com"
                        }
                        """.formatted(title));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult postNote(UUID tenantId, UUID claimId, String idempotencyKey, String body)
            throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/claims/{claimId}/notes", claimId)
                .header("X-EIA-Tenant", tenantId)
                .header("X-EIA-Book", "DIRECT")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "noteType": "DIARY",
                          "body": "%s",
                          "author": "adjuster@example.com"
                        }
                        """.formatted(body));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request).andReturn();
    }

    private Claim createClaim(UUID tenantId) {
        return claimService.create(new CreateClaimRequest(
                tenantId, POLICY_ID, CLAIMANT_ID,
                LocalDate.of(2026, 3, 14), LocalDate.of(2026, 3, 18),
                "GL", "PROPERTY_DAMAGE", new BigDecimal("0.00"), "USD",
                new BigDecimal("500000.00"), "USD", null));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private int claimVersion(UUID claimId) {
        return jdbc.queryForObject("SELECT version FROM claim WHERE id = ?", Integer.class, claimId);
    }

    private int countEvents(UUID claimId, String eventType) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = ?
                """, Integer.class, claimId, eventType);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
