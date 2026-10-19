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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/claims/{claimId}/settlements")
public class SettlementController {
    private final SettlementService service;

    public SettlementController(SettlementService service) {
        this.service = service;
    }

    @GetMapping
    public List<Settlement> list(@RequestParam("tenantId") UUID tenantId,
                                 @RequestHeader(value = "X-EIA-Book", required = false) String book,
                                 @PathVariable UUID claimId) {
        BookWall.requireDirect(book);
        return service.list(tenantId, claimId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Settlement propose(@RequestHeader("X-EIA-Tenant") UUID tenantId,
                              @RequestHeader(value = "X-EIA-Book", required = false) String book,
                              @PathVariable UUID claimId,
                              @Valid @RequestBody SettlementRequest request) {
        BookWall.requireDirect(book);
        return service.propose(tenantId, claimId, request);
    }

    @PostMapping("/{settlementId}/approve")
    public Settlement approve(@RequestHeader("X-EIA-Tenant") UUID tenantId,
                              @RequestHeader(value = "X-EIA-Book", required = false) String book,
                              @PathVariable UUID claimId,
                              @PathVariable UUID settlementId,
                              @Valid @RequestBody SettlementApprovalRequest request) {
        BookWall.requireDirect(book);
        return service.approve(tenantId, claimId, settlementId, request);
    }

    @PostMapping("/{settlementId}/execute")
    public Settlement execute(@RequestHeader("X-EIA-Tenant") UUID tenantId,
                              @RequestHeader(value = "X-EIA-Book", required = false) String book,
                              @PathVariable UUID claimId,
                              @PathVariable UUID settlementId,
                              @Valid @RequestBody SettlementExecutionRequest request) {
        BookWall.requireDirect(book);
        return service.execute(tenantId, claimId, settlementId, request);
    }
}
