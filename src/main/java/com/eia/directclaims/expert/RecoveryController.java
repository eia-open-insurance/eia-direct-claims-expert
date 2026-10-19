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
@RequestMapping("/internal/v1/claims/{claimId}/recoveries")
public class RecoveryController {
    private final RecoveryService service;

    public RecoveryController(RecoveryService service) {
        this.service = service;
    }

    @GetMapping
    public List<Recovery> list(@RequestParam("tenantId") UUID tenantId,
                               @RequestHeader(value = "X-EIA-Book", required = false) String book,
                               @PathVariable UUID claimId) {
        BookWall.requireDirect(book);
        return service.list(tenantId, claimId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Recovery create(@RequestHeader("X-EIA-Tenant") UUID tenantId,
                           @RequestHeader(value = "X-EIA-Book", required = false) String book,
                           @PathVariable UUID claimId,
                           @Valid @RequestBody RecoveryRequest request) {
        BookWall.requireDirect(book);
        return service.record(tenantId, claimId, request);
    }

    @PatchMapping("/{recoveryId}")
    public Recovery update(@RequestHeader("X-EIA-Tenant") UUID tenantId,
                           @RequestHeader(value = "X-EIA-Book", required = false) String book,
                           @PathVariable UUID claimId,
                           @PathVariable UUID recoveryId,
                           @Valid @RequestBody RecoveryRequest request) {
        BookWall.requireDirect(book);
        return service.changeStatus(tenantId, claimId, recoveryId, request);
    }
}
