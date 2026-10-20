package com.eia.directclaims.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Claim tasks. The public route is {@code /api/v1/claims/{claimId}/tasks}; the
 * internal alias matches the expert service's other child routes. Every route
 * is tenant-scoped and returns 404 outside the DIRECT book.
 *
 * <p>The create route accepts an optional {@code Idempotency-Key}; when one is
 * supplied the response carries {@code Idempotent-Replay: true} for a replay of
 * the stored 201 response and {@code false} for a fresh create.</p>
 */
@RestController
@RequestMapping({
        "/api/v1/claims/{claimId}/tasks",
        "/internal/v1/claims/{claimId}/tasks"
})
public class ClaimTaskController {
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAY = "Idempotent-Replay";

    private final ClaimTaskService service;

    public ClaimTaskController(ClaimTaskService service) {
        this.service = service;
    }

    @GetMapping
    public List<ClaimTask> list(@RequestParam(value = "tenantId", required = false) UUID tenantId,
                                @RequestHeader(value = "X-EIA-Tenant", required = false) UUID tenantHeader,
                                @RequestHeader(value = "X-EIA-Book", required = false) String book,
                                @PathVariable UUID claimId) {
        BookWall.requireDirect(book);
        return service.list(TenantHeader.resolve(tenantId, tenantHeader), claimId);
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestHeader("X-EIA-Tenant") UUID tenantId,
                                         @RequestHeader(value = "X-EIA-Book", required = false) String book,
                                         @PathVariable UUID claimId,
                                         @RequestHeader(value = IDEMPOTENCY_KEY, required = false)
                                         String idempotencyKey,
                                         @Valid @RequestBody CreateTaskRequest request) {
        BookWall.requireDirect(book);
        ClaimCommandResult<ClaimTask> result = service.create(tenantId, claimId, request,
                idempotencyKey);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON);
        if (result.idempotent()) {
            response.header(IDEMPOTENT_REPLAY, Boolean.toString(result.replayed()));
        }
        return response.body(result.body());
    }

    @PatchMapping("/{taskId}")
    public ClaimTask changeStatus(@RequestHeader("X-EIA-Tenant") UUID tenantId,
                                  @RequestHeader(value = "X-EIA-Book", required = false) String book,
                                  @PathVariable UUID claimId,
                                  @PathVariable UUID taskId,
                                  @Valid @RequestBody TaskStatusChangeRequest request) {
        BookWall.requireDirect(book);
        return service.changeStatus(tenantId, claimId, taskId, request);
    }
}
