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

@RestController
@RequestMapping("/internal/v1/claims")
public class ClaimController {
    private final ClaimService service;

    public ClaimController(ClaimService service) {
        this.service = service;
    }

    @GetMapping
    public List<Claim> list(@RequestParam("tenantId") UUID tenantId,
                            @RequestHeader(value = "X-EIA-Book", required = false) String book,
                            @RequestParam(value = "status", required = false) String status) {
        BookWall.requireDirect(book);
        return service.list(tenantId, status);
    }

    @GetMapping("/{id}")
    public Claim get(@RequestParam("tenantId") UUID tenantId,
                     @RequestHeader(value = "X-EIA-Book", required = false) String book,
                     @PathVariable UUID id) {
        BookWall.requireDirect(book);
        return service.get(tenantId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Claim create(@RequestHeader(value = "X-EIA-Book", required = false) String book,
                        @Valid @RequestBody CreateClaimRequest request) {
        BookWall.requireDirect(book);
        return service.create(request);
    }

    @PatchMapping("/{id}/status")
    public Claim changeStatus(@RequestHeader("X-EIA-Tenant") UUID tenantId,
                              @RequestHeader(value = "X-EIA-Book", required = false) String book,
                              @PathVariable UUID id,
                              @Valid @RequestBody StatusChangeRequest request) {
        BookWall.requireDirect(book);
        return service.changeStatus(tenantId, id, request.status(), request.expectedVersion());
    }

    @PostMapping("/{id}/close")
    public Claim close(@RequestHeader("X-EIA-Tenant") UUID tenantId,
                       @RequestHeader(value = "X-EIA-Book", required = false) String book,
                       @PathVariable UUID id,
                       @RequestBody(required = false) CloseClaimRequest request) {
        BookWall.requireDirect(book);
        return service.close(tenantId, id, request == null ? null : request.expectedVersion());
    }
}
