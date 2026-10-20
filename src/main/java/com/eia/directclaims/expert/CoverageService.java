package com.eia.directclaims.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class CoverageService {
    private final CoverageRepository repository;
    private final InjuryRepository injuryRepository;
    private final ExposureRepository exposureRepository;
    private final ClaimLookup claims;
    private final OutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public CoverageService(CoverageRepository repository,
                           InjuryRepository injuryRepository,
                           ExposureRepository exposureRepository,
                           ClaimLookup claims,
                           OutboxRepository outbox) {
        this(repository, injuryRepository, exposureRepository, claims, outbox, Clock.systemUTC());
    }

    CoverageService(CoverageRepository repository,
                    InjuryRepository injuryRepository,
                    ExposureRepository exposureRepository,
                    ClaimLookup claims,
                    OutboxRepository outbox,
                    Clock clock) {
        this.repository = repository;
        this.injuryRepository = injuryRepository;
        this.exposureRepository = exposureRepository;
        this.claims = claims;
        this.outbox = outbox;
        this.clock = clock;
    }

    public CoverageDecision get(UUID tenantId, UUID claimId) {
        claims.require(tenantId, claimId);
        return repository.findLatestFinal(tenantId, claimId)
                .orElseThrow(() -> new CoverageDecisionNotFoundException(claimId));
    }

    /**
     * Records a final coverage decision and appends its event in the same
     * transaction. The repository bumps the claim version as part of the
     * decision, so the event version is the pre-change version plus one.
     */
    @Transactional
    public CoverageDecision decide(UUID tenantId, UUID claimId, CoverageDecisionRequest request) {
        Claim claim = claims.require(tenantId, claimId);
        String position = Vocab.normalize(Vocab.COVERAGE_POSITIONS, request.position(), "position");
        String basis = Vocab.requiredText(request.basis(), "basis", 4000);
        UUID reviewer = Vocab.requiredId(request.reviewerPartyId(), "reviewerPartyId");
        var decisionDate = Vocab.requiredDate(request.decisionDate(), "decisionDate");
        String allocationMethod = Vocab.optional(
                Vocab.ALLOCATION_METHODS, request.allocationMethod(), "allocationMethod");
        BigDecimal allocationPct = request.allocationPct() == null
                ? null : request.allocationPct().setScale(6, RoundingMode.HALF_UP);
        if (allocationPct != null
                && (allocationPct.signum() < 0 || allocationPct.compareTo(BigDecimal.ONE) > 0)) {
            throw new ClaimValidationException("allocationPct must be between 0 and 1");
        }
        if (!"DENIED".equals(position)) {
            requireInjuryOrExposure(claim);
        }
        Instant now = Instant.now(clock);
        CoverageDecision decision = repository.decide(claim, position, basis,
                Vocab.optionalText(request.policyProvisions(), 8000), allocationMethod, allocationPct,
                decisionDate, reviewer, now);
        int claimVersion = claim.version() + 1;
        outbox.insert(OutboxEvent.fromEnvelope(
                ClaimEvents.coverageAssessed(claim, decision, claimVersion, now)));
        return decision;
    }

    /**
     * DIR-02: a bodily-injury or liability claim cannot be accepted for coverage
     * before at least one injury or exposure is recorded; mass tort and
     * environmental claims additionally require an exposure.
     */
    private void requireInjuryOrExposure(Claim claim) {
        if (!Vocab.INJURY_REQUIRED_TYPES.contains(claim.claimType())) {
            return;
        }
        long injuries = injuryRepository.count(claim.tenantId(), claim.id());
        long exposures = exposureRepository.count(claim.tenantId(), claim.id());
        if (injuries + exposures == 0) {
            throw new ClaimValidationException(
                    "Coverage cannot be accepted before at least one injury or exposure is recorded");
        }
        if (Vocab.EXPOSURE_REQUIRED_TYPES.contains(claim.claimType()) && exposures == 0) {
            throw new ClaimValidationException(
                    "Coverage for a " + claim.claimType()
                            + " claim cannot be accepted before at least one exposure is recorded");
        }
    }
}
