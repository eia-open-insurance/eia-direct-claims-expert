package com.eia.directclaims.expert;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class LienService {
    private final LienRepository repository;
    private final ClaimLookup claims;
    private final Clock clock;

    @Autowired
    public LienService(LienRepository repository, ClaimLookup claims) {
        this(repository, claims, Clock.systemUTC());
    }

    LienService(LienRepository repository, ClaimLookup claims, Clock clock) {
        this.repository = repository;
        this.claims = claims;
        this.clock = clock;
    }

    public List<Lien> list(UUID tenantId, UUID claimId) {
        claims.require(tenantId, claimId);
        return repository.findAll(tenantId, claimId);
    }

    public Lien record(UUID tenantId, UUID claimId, LienRequest request) {
        claims.require(tenantId, claimId);
        String type = Vocab.normalize(Vocab.LIEN_TYPES, request.lienType(), "lienType");
        String status = Vocab.normalize(Vocab.LIEN_STATUSES, request.status(), "status");
        String resolution = Vocab.optional(Vocab.LIEN_RESOLUTIONS, request.resolutionType(), "resolutionType");
        Instant resolvedAt = null;
        if (Vocab.TERMINAL_LIEN_STATUSES.contains(status)) {
            if (resolution == null) {
                throw new ClaimValidationException("resolutionType is required to resolve a lien");
            }
            resolvedAt = Instant.now(clock);
        }
        BigDecimal amount = Vocab.amount(request.amount(), "amount", true);
        return repository.insert(UuidV7.next(), tenantId, claimId, type,
                Vocab.requiredText(request.lienholder(), "lienholder", 160), amount,
                Vocab.currency(request.currency(), "USD"), status, resolution, resolvedAt, Instant.now(clock));
    }

    public Lien update(UUID tenantId, UUID claimId, UUID lienId, LienRequest request) {
        claims.require(tenantId, claimId);
        Lien existing = repository.findById(tenantId, claimId, lienId)
                .orElseThrow(() -> RecordNotFoundException.of("Lien", lienId));
        int expectedVersion = requireExpectedVersion(request.expectedVersion());
        if (expectedVersion != existing.version()) {
            throw new ClaimConflictException("Lien " + lienId + " version " + expectedVersion
                    + " does not match current version " + existing.version());
        }
        String status = request.status() == null
                ? existing.status() : Vocab.normalize(Vocab.LIEN_STATUSES, request.status(), "status");
        String resolution = request.resolutionType() == null
                ? existing.resolutionType()
                : Vocab.optional(Vocab.LIEN_RESOLUTIONS, request.resolutionType(), "resolutionType");
        Instant resolvedAt = null;
        if (Vocab.TERMINAL_LIEN_STATUSES.contains(status)) {
            if (resolution == null) {
                throw new ClaimValidationException("resolutionType is required to resolve a lien");
            }
            resolvedAt = existing.resolvedAt() == null ? Instant.now(clock) : existing.resolvedAt();
        }
        return repository.update(tenantId, claimId, lienId, status, resolution, resolvedAt,
                expectedVersion, Instant.now(clock));
    }

    private static int requireExpectedVersion(Integer expectedVersion) {
        if (expectedVersion == null) {
            throw new ClaimValidationException("expectedVersion is required");
        }
        return expectedVersion;
    }
}
