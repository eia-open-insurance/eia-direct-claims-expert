package com.eia.directclaims.expert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.eia.directclaims.expert.Fixtures.CLAIM_ID;
import static com.eia.directclaims.expert.Fixtures.TASK_ID;
import static com.eia.directclaims.expert.Fixtures.TENANT;
import static com.eia.directclaims.expert.Fixtures.note;
import static com.eia.directclaims.expert.Fixtures.task;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {ClaimTaskController.class, ClaimNoteController.class})
class ClaimTaskNoteEndpointTest {
    private static final String BASE = "/api/v1/claims/" + CLAIM_ID;
    private static final String TENANT_PARAM = TENANT.toString();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ClaimTaskService taskService;
    @MockitoBean
    private ClaimNoteService noteService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    // -------------------------------------------------------------------- tasks
    @Test
    void listsTasks() throws Exception {
        when(taskService.list(TENANT, CLAIM_ID))
                .thenReturn(List.of(task("OPEN", 1), task("COMPLETE", 2)));

        mvc.perform(get(BASE + "/tasks").param("tenantId", TENANT_PARAM)
                        .header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].priority").value("HIGH"))
                .andExpect(jsonPath("$[0].dueDate").value("2026-04-15"))
                .andExpect(jsonPath("$[1].status").value("COMPLETE"));
    }

    @Test
    void listsTasksWithTheTenantHeader() throws Exception {
        when(taskService.list(TENANT, CLAIM_ID)).thenReturn(List.of(task("OPEN", 1)));

        mvc.perform(get(BASE + "/tasks").header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taskNumber").value("TSK-2026-0000001"));
    }

    @Test
    void createsATask() throws Exception {
        when(taskService.create(eq(TENANT), eq(CLAIM_ID), any(CreateTaskRequest.class), isNull()))
                .thenReturn(result(task("OPEN", 1), false, false));

        mvc.perform(post(BASE + "/tasks")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Review medical records",
                                  "description": "Order records and index the file",
                                  "dueDate": "2026-04-15",
                                  "priority": "HIGH",
                                  "assignedTo": "adjuster@example.com"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replay"))
                .andExpect(jsonPath("$.taskNumber").value("TSK-2026-0000001"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(1));

        verify(taskService).create(eq(TENANT), eq(CLAIM_ID), any(CreateTaskRequest.class), isNull());
    }

    @Test
    void repeatsATaskCreateWithTheIdempotentReplayHeader() throws Exception {
        when(taskService.create(eq(TENANT), eq(CLAIM_ID), any(CreateTaskRequest.class),
                eq("task-key-1"))).thenReturn(result(task("OPEN", 1), true, true));

        mvc.perform(post(BASE + "/tasks")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .header("Idempotency-Key", "task-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Review medical records",
                                  "description": "Order records and index the file",
                                  "dueDate": "2026-04-15",
                                  "priority": "HIGH",
                                  "assignedTo": "adjuster@example.com"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.taskNumber").value("TSK-2026-0000001"));
    }

    @Test
    void rejectsAnIdempotencyKeyReuseWithADifferentTaskBody() throws Exception {
        when(taskService.create(eq(TENANT), eq(CLAIM_ID), any(CreateTaskRequest.class),
                eq("task-key-1"))).thenThrow(new IdempotencyKeyReusedException(
                        "Idempotency-Key was already used with a different request."));

        mvc.perform(post(BASE + "/tasks")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .header("Idempotency-Key", "task-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Different\", \"dueDate\": \"2026-04-15\", \"priority\": \"HIGH\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EIA-DIR-4091"))
                .andExpect(jsonPath("$.category").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void completesATask() throws Exception {
        when(taskService.changeStatus(eq(TENANT), eq(CLAIM_ID), eq(TASK_ID),
                any(TaskStatusChangeRequest.class))).thenReturn(task("COMPLETE", 2));

        mvc.perform(patch(BASE + "/tasks/" + TASK_ID)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"COMPLETE\", \"version\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());
    }

    @Test
    void taskRoutesStayInsideTheBookWall() throws Exception {
        mvc.perform(get(BASE + "/tasks").param("tenantId", TENANT_PARAM)
                        .header("X-EIA-Book", "ASSUMED"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(post(BASE + "/tasks")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "ASSUMED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Review medical records",
                                  "dueDate": "2026-04-15",
                                  "priority": "HIGH"
                                }
                                """))
                .andExpect(status().isNotFound());
        mvc.perform(patch(BASE + "/tasks/" + TASK_ID)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "ASSUMED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"COMPLETE\", \"version\": 1}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(taskService);
    }

    @Test
    void taskValidationRejectsMissingTitleAndDueDate() throws Exception {
        mvc.perform(post(BASE + "/tasks")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\": \"HIGH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[*].field", hasItems("title", "dueDate")));

        verifyNoInteractions(taskService);
    }

    @Test
    void taskPatchRequiresAVersion() throws Exception {
        mvc.perform(patch(BASE + "/tasks/" + TASK_ID)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"COMPLETE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[*].field", hasItem("version")));

        verifyNoInteractions(taskService);
    }

    @Test
    void staleTaskVersionIs409() throws Exception {
        when(taskService.changeStatus(eq(TENANT), eq(CLAIM_ID), eq(TASK_ID),
                any(TaskStatusChangeRequest.class)))
                .thenThrow(new ClaimConflictException("Task version 2 is stale"));

        mvc.perform(patch(BASE + "/tasks/" + TASK_ID)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"COMPLETE\", \"version\": 2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void missingTaskIs404() throws Exception {
        when(taskService.changeStatus(eq(TENANT), eq(CLAIM_ID), eq(TASK_ID),
                any(TaskStatusChangeRequest.class)))
                .thenThrow(RecordNotFoundException.of("Claim task", TASK_ID));

        mvc.perform(patch(BASE + "/tasks/" + TASK_ID)
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"COMPLETE\", \"version\": 1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // -------------------------------------------------------------------- notes
    @Test
    void listsNotes() throws Exception {
        when(noteService.list(TENANT, CLAIM_ID))
                .thenReturn(List.of(note("SETTLEMENT", "Second"), note("DIARY", "First")));

        mvc.perform(get(BASE + "/notes").param("tenantId", TENANT_PARAM)
                        .header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].noteType").value("SETTLEMENT"))
                .andExpect(jsonPath("$[1].noteType").value("DIARY"));
    }

    @Test
    void createsANote() throws Exception {
        when(noteService.add(eq(TENANT), eq(CLAIM_ID), any(CreateNoteRequest.class), isNull()))
                .thenReturn(result(note("DIARY", "Called the claimant"), false, false));

        mvc.perform(post(BASE + "/notes")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "noteType": "DIARY",
                                  "body": "Called the claimant",
                                  "author": "adjuster@example.com"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replay"))
                .andExpect(jsonPath("$.noteNumber").value("NTE-2026-0000001"))
                .andExpect(jsonPath("$.noteType").value("DIARY"))
                .andExpect(jsonPath("$.author").value("adjuster@example.com"));

        verify(noteService).add(eq(TENANT), eq(CLAIM_ID), any(CreateNoteRequest.class), isNull());
    }

    @Test
    void repeatsANoteCreateWithTheIdempotentReplayHeader() throws Exception {
        when(noteService.add(eq(TENANT), eq(CLAIM_ID), any(CreateNoteRequest.class),
                eq("note-key-1"))).thenReturn(result(note("DIARY", "Called the claimant"), true, true));

        mvc.perform(post(BASE + "/notes")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .header("Idempotency-Key", "note-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "noteType": "DIARY",
                                  "body": "Called the claimant",
                                  "author": "adjuster@example.com"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.noteNumber").value("NTE-2026-0000001"));
    }

    @Test
    void noteRoutesStayInsideTheBookWall() throws Exception {
        mvc.perform(get(BASE + "/notes").param("tenantId", TENANT_PARAM)
                        .header("X-EIA-Book", "ASSUMED"))
                .andExpect(status().isNotFound());
        mvc.perform(post(BASE + "/notes")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "ASSUMED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "noteType": "DIARY",
                                  "body": "Called the claimant",
                                  "author": "adjuster@example.com"
                                }
                                """))
                .andExpect(status().isNotFound());
        verifyNoInteractions(noteService);
    }

    @Test
    void noteValidationRejectsAnEmptyBody() throws Exception {
        mvc.perform(post(BASE + "/notes")
                        .header("X-EIA-Tenant", TENANT)
                        .header("X-EIA-Book", "DIRECT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"noteType\": \"DIARY\", \"author\": \"adjuster@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[*].field", hasItem("body")));

        verifyNoInteractions(noteService);
    }

    @Test
    void noteReadRequiresATenant() throws Exception {
        mvc.perform(get(BASE + "/notes").header("X-EIA-Book", "DIRECT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(noteService);
    }

    private <T> ClaimCommandResult<T> result(T value, boolean replayed, boolean idempotent) {
        try {
            return new ClaimCommandResult<>(value, objectMapper.writeValueAsString(value), replayed,
                    idempotent);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
