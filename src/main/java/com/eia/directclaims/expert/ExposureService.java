package com.eia.directclaims.expert;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class ExposureService {
    private final ExposureRepository repository;
    private final ClaimLookup claims;
    private final Clock clock;

    @Autowired
    public ExposureService(ExposureRepository repository, ClaimLookup claims) {
        this(repository, claims, Clock.systemUTC());
    }

    ExposureService(ExposureRepository repository, ClaimLookup claims, Clock clock) {
        this.repository = repository;
        this.claims = claims;
        this.clock = clock;
    }

    public List<ClaimExposure> list(UUID tenantId, UUID claimId) {
        claims.require(tenantId, claimId);
        return repository.findAll(tenantId, claimId);
    }

    public ClaimExposure add(UUID tenantId, UUID claimId, ExposureRequest request) {
        Claim claim = claims.require(tenantId, claimId);
        claims.requireClaimant(claim, request.claimantPartyId());
        String exposureType = Vocab.normalize(Vocab.EXPOSURE_TYPES, request.exposureType(), "exposureType");
        LocalDate from = Vocab.requiredDate(request.exposureFrom(), "exposureFrom");
        validateDates(claim, from, request.exposureTo());
        return repository.insert(UuidV7.next(), tenantId, claimId, request.claimantPartyId(), exposureType,
                Vocab.optionalText(request.productName(), 200), Vocab.optionalText(request.substanceName(), 160),
                Vocab.optionalText(request.siteName(), 200), request.employerPartyId(),
                Vocab.optionalText(request.jobTitle(), 120),
                Vocab.requiredText(request.jurisdiction(), "jurisdiction", 120), from, request.exposureTo(),
                Vocab.optional(Vocab.INTENSITIES, request.intensity(), "intensity"), Instant.now(clock));
    }

    public ClaimExposure update(UUID tenantId, UUID claimId, UUID exposureId, ExposureRequest request) {
        Claim claim = claims.require(tenantId, claimId);
        ClaimExposure existing = repository.findById(tenantId, claimId, exposureId)
                .orElseThrow(() -> RecordNotFoundException.of("Exposure", exposureId));
        int expectedVersion = requireExpectedVersion(request.expectedVersion());
        if (expectedVersion != existing.version()) {
            throw new ClaimConflictException("Exposure " + exposureId + " version " + expectedVersion
                    + " does not match current version " + existing.version());
        }
        UUID claimant = request.claimantPartyId() == null
                ? existing.claimantPartyId() : request.claimantPartyId();
        claims.requireClaimant(claim, claimant);
        String exposureType = request.exposureType() == null
                ? existing.exposureType()
                : Vocab.normalize(Vocab.EXPOSURE_TYPES, request.exposureType(), "exposureType");
        LocalDate from = request.exposureFrom() == null ? existing.exposureFrom() : request.exposureFrom();
        LocalDate to = request.exposureFrom() == null && request.exposureTo() == null
                ? existing.exposureTo() : request.exposureTo();
        validateDates(claim, from, to);
        return repository.update(tenantId, claimId, exposureId, claimant, exposureType,
                request.productName() == null
                        ? existing.productName() : Vocab.optionalText(request.productName(), 200),
                request.substanceName() == null
                        ? existing.substanceName() : Vocab.optionalText(request.substanceName(), 160),
                request.siteName() == null
                        ? existing.siteName() : Vocab.optionalText(request.siteName(), 200),
                request.employerPartyId() == null ? existing.employerPartyId() : request.employerPartyId(),
                request.jobTitle() == null
                        ? existing.jobTitle() : Vocab.optionalText(request.jobTitle(), 120),
                request.jurisdiction() == null
                        ? existing.jurisdiction()
                        : Vocab.requiredText(request.jurisdiction(), "jurisdiction", 120),
                from, to,
                request.intensity() == null
                        ? existing.intensity() : Vocab.optional(Vocab.INTENSITIES, request.intensity(), "intensity"),
                expectedVersion, Instant.now(clock));
    }

    /**
     * An exposure cannot post-date first notice of loss: the claim report date
     * is the latest date an asserted exposure can carry.
     */
    private static void validateDates(Claim claim, LocalDate from, LocalDate to) {
        if (from.isAfter(claim.reportDate())) {
            throw new ClaimValidationException("exposureFrom cannot be after reportDate "
                    + claim.reportDate());
        }
        if (to != null) {
            if (to.isBefore(from)) {
                throw new ClaimValidationException("exposureTo cannot be before exposureFrom");
            }
            if (to.isAfter(claim.reportDate())) {
                throw new ClaimValidationException("exposureTo cannot be after reportDate "
                        + claim.reportDate());
            }
        }
    }

    private static int requireExpectedVersion(Integer expectedVersion) {
        if (expectedVersion == null) {
            throw new ClaimValidationException("expectedVersion is required");
        }
        return expectedVersion;
    }
}
