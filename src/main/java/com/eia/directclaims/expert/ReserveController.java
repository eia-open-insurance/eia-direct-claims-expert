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

@RestController
@RequestMapping("/internal/v1/claims/{claimId}/reserves")
public class ReserveController {
    private final ReserveService service;

    public ReserveController(ReserveService service) {
        this.service = service;
    }

    @GetMapping
    public ReserveSummary summary(@RequestParam("tenantId") UUID tenantId,
                                  @RequestHeader(value = "X-EIA-Book", required = false) String book,
                                  @PathVariable UUID claimId) {
        BookWall.requireDirect(book);
        return service.summary(tenantId, claimId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReserveHistoryEntry change(@RequestHeader("X-EIA-Tenant") UUID tenantId,
                                      @RequestHeader(value = "X-EIA-Book", required = false) String book,
                                      @PathVariable UUID claimId,
                                      @Valid @RequestBody ReserveChangeRequest request) {
        BookWall.requireDirect(book);
        return service.change(tenantId, claimId, request);
    }
}
