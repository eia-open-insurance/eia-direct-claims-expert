package com.eia.directclaims.expert;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class LitigationService {
    private final LitigationRepository repository;
    private final ClaimRepository claimRepository;
    private final ClaimLookup claims;
    private final Clock clock;

    @Autowired
    public LitigationService(LitigationRepository repository,
                             ClaimRepository claimRepository,
                             ClaimLookup claims) {
        this(repository, claimRepository, claims, Clock.systemUTC());
    }

    LitigationService(LitigationRepository repository,
                      ClaimRepository claimRepository,
                      ClaimLookup claims,
                      Clock clock) {
        this.repository = repository;
        this.claimRepository = claimRepository;
        this.claims = claims;
        this.clock = clock;
    }

    public List<LitigationMatter> list(UUID tenantId, UUID claimId) {
        claims.require(tenantId, claimId);
        return repository.findAll(tenantId, claimId);
    }

    public LitigationMatter add(UUID tenantId, UUID claimId, LitigationRequest request) {
        Claim claim = claims.require(tenantId, claimId);
        String matterType = Vocab.normalize(Vocab.MATTER_TYPES, request.matterType(), "matterType");
        String jurisdiction = Vocab.requiredText(request.jurisdiction(), "jurisdiction", 120);
        UUID counsel = Vocab.requiredId(request.counselPartyId(), "counselPartyId");
        LocalDate filingDate = Vocab.requiredDate(request.filingDate(), "filingDate");
        validateDates(filingDate, request.trialDate());
        String status = Vocab.normalize(Vocab.LITIGATION_STATUSES, request.status(), "status");
        LitigationMatter matter = repository.insert(UuidV7.next(), tenantId, claimId, matterType,
                jurisdiction, counsel, filingDate, request.trialDate(), status,
                Vocab.optionalText(request.caseNumber(), 64), Instant.now(clock));
        claimRepository.markLitigation(tenantId, claimId, Instant.now(clock));
        return matter;
    }

    public LitigationMatter update(UUID tenantId, UUID claimId, UUID litigationId, LitigationRequest request) {
        Claim claim = claims.require(tenantId, claimId);
        LitigationMatter existing = repository.findById(tenantId, claimId, litigationId)
                .orElseThrow(() -> RecordNotFoundException.of("Litigation matter", litigationId));
        int expectedVersion = request.expectedVersion() == null
                ? claim.version() : request.expectedVersion();
        if (expectedVersion != existing.version()) {
            throw new ClaimConflictException("Litigation matter " + litigationId + " version "
                    + expectedVersion + " does not match current version " + existing.version());
        }
        String matterType = request.matterType() == null
                ? existing.matterType()
                : Vocab.normalize(Vocab.MATTER_TYPES, request.matterType(), "matterType");
        String jurisdiction = request.jurisdiction() == null
                ? existing.jurisdiction()
                : Vocab.requiredText(request.jurisdiction(), "jurisdiction", 120);
        UUID counsel = request.counselPartyId() == null ? existing.counselPartyId() : request.counselPartyId();
        LocalDate filingDate = request.filingDate() == null ? existing.filingDate() : request.filingDate();
        LocalDate trialDate = request.trialDate() == null ? existing.trialDate() : request.trialDate();
        validateDates(filingDate, trialDate);
        String status = request.status() == null
                ? existing.status()
                : Vocab.normalize(Vocab.LITIGATION_STATUSES, request.status(), "status");
        return repository.update(tenantId, claimId, litigationId, matterType, jurisdiction, counsel,
                filingDate, trialDate, status,
                request.caseNumber() == null
                        ? existing.caseNumber() : Vocab.optionalText(request.caseNumber(), 64),
                expectedVersion, Instant.now(clock));
    }

    private static void validateDates(LocalDate filingDate, LocalDate trialDate) {
        if (trialDate != null && trialDate.isBefore(filingDate)) {
            throw new ClaimValidationException("trialDate cannot be before filingDate");
        }
    }
}
