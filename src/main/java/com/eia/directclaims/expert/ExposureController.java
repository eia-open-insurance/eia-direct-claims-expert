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
@RequestMapping("/internal/v1/claims/{claimId}/exposures")
public class ExposureController {
    private final ExposureService service;

    public ExposureController(ExposureService service) {
        this.service = service;
    }

    @GetMapping
    public List<ClaimExposure> list(@RequestParam("tenantId") UUID tenantId,
                                    @RequestHeader(value = "X-EIA-Book", required = false) String book,
                                    @PathVariable UUID claimId) {
        BookWall.requireDirect(book);
        return service.list(tenantId, claimId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClaimExposure create(@RequestHeader("X-EIA-Tenant") UUID tenantId,
                                @RequestHeader(value = "X-EIA-Book", required = false) String book,
                                @PathVariable UUID claimId,
                                @Valid @RequestBody ExposureRequest request) {
        BookWall.requireDirect(book);
        return service.add(tenantId, claimId, request);
    }

    @PatchMapping("/{exposureId}")
    public ClaimExposure update(@RequestHeader("X-EIA-Tenant") UUID tenantId,
                                @RequestHeader(value = "X-EIA-Book", required = false) String book,
                                @PathVariable UUID claimId,
                                @PathVariable UUID exposureId,
                                @Valid @RequestBody ExposureRequest request) {
        BookWall.requireDirect(book);
        return service.update(tenantId, claimId, exposureId, request);
    }
}
