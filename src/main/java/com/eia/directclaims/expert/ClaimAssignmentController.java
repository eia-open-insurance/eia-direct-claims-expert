package com.eia.directclaims.expert;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Claim assignment. The public route is
 * {@code /api/v1/claims/{claimId}/assignment}; the internal alias matches the
 * expert service's other claim routes. A non-DIRECT book is a 404 before the
 * service is called.
 *
 * <p>The acting identity is taken from the optional canonical
 * {@code X-EIA-User} header (the legacy {@code X-EIA-Actor} header is also
 * accepted) and defaults to the assigned adjuster when neither is present.</p>
 */
@RestController
@RequestMapping({
        "/api/v1/claims/{claimId}/assignment",
        "/internal/v1/claims/{claimId}/assignment"
})
public class ClaimAssignmentController {
    private final ClaimService service;

    public ClaimAssignmentController(ClaimService service) {
        this.service = service;
    }

    @PatchMapping
    public Claim assign(@RequestHeader("X-EIA-Tenant") UUID tenantId,
                        @RequestHeader(value = "X-EIA-Book", required = false) String book,
                        @RequestHeader(value = "X-EIA-User", required = false) String user,
                        @RequestHeader(value = "X-EIA-Actor", required = false) String actor,
                        @PathVariable UUID claimId,
                        @Valid @RequestBody ClaimAssignmentRequest request) {
        BookWall.requireDirect(book);
        return service.assign(tenantId, claimId, request, actingUser(user, actor));
    }

    private static String actingUser(String user, String actor) {
        return user != null && !user.isBlank() ? user : actor;
    }
}
