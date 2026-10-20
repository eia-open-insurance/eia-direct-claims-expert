package com.eia.directclaims.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Claim tasks. The public route is {@code /api/v1/claims/{claimId}/tasks}; the
 * internal alias matches the expert service's other child routes. Every route
 * is tenant-scoped and returns 404 outside the DIRECT book.
 */
@RestController
@RequestMapping({
        "/api/v1/claims/{claimId}/tasks",
        "/internal/v1/claims/{claimId}/tasks"
})
public class ClaimTaskController {
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
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimTask create(@RequestHeader("X-EIA-Tenant") UUID tenantId,
                            @RequestHeader(value = "X-EIA-Book", required = false) String book,
                            @PathVariable UUID claimId,
                            @Valid @RequestBody CreateTaskRequest request) {
        BookWall.requireDirect(book);
        return service.create(tenantId, claimId, request);
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
