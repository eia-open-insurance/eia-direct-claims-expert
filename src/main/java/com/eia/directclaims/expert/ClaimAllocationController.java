package com.eia.directclaims.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Claim allocations. The public route is
 * {@code /api/v1/claims/{claimId}/allocations}; the Direct Claims domain
 * service uses the internal alias. Reads accept {@code ?tenantId=} or
 * {@code X-EIA-Tenant}; writes take {@code X-EIA-Tenant}. Every route is
 * tenant-scoped and returns 404 outside the DIRECT book.
 */
@RestController
@RequestMapping({
        "/api/v1/claims/{claimId}/allocations",
        "/internal/v1/claims/{claimId}/allocations"
})
public class ClaimAllocationController {
    private final ClaimAllocationService service;

    public ClaimAllocationController(ClaimAllocationService service) {
        this.service = service;
    }

    @GetMapping
    public ClaimAllocationSummary list(@RequestParam(value = "tenantId", required = false) UUID tenantId,
                                       @RequestHeader(value = "X-EIA-Tenant", required = false)
                                       UUID tenantHeader,
                                       @RequestHeader(value = "X-EIA-Book", required = false) String book,
                                       @PathVariable UUID claimId) {
        BookWall.requireDirect(book);
        return service.list(TenantHeader.resolve(tenantId, tenantHeader), claimId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimAllocation create(@RequestHeader("X-EIA-Tenant") UUID tenantId,
                                  @RequestHeader(value = "X-EIA-Book", required = false) String book,
                                  @PathVariable UUID claimId,
                                  @Valid @RequestBody CreateClaimAllocationRequest request) {
        BookWall.requireDirect(book);
        return service.create(tenantId, claimId, request);
    }
}
