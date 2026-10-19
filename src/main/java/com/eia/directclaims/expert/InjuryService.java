package com.eia.directclaims.expert;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class InjuryService {
    private final InjuryRepository repository;
    private final ClaimLookup claims;
    private final Clock clock;

    @Autowired
    public InjuryService(InjuryRepository repository, ClaimLookup claims) {
        this(repository, claims, Clock.systemUTC());
    }

    InjuryService(InjuryRepository repository, ClaimLookup claims, Clock clock) {
        this.repository = repository;
        this.claims = claims;
        this.clock = clock;
    }

    public List<ClaimInjury> list(UUID tenantId, UUID claimId) {
        claims.require(tenantId, claimId);
        return repository.findAll(tenantId, claimId);
    }

    public ClaimInjury add(UUID tenantId, UUID claimId, InjuryRequest request) {
        Claim claim = claims.require(tenantId, claimId);
        claims.requireClaimant(claim, request.claimantPartyId());
        String injuryType = Vocab.normalize(Vocab.INJURY_TYPES, request.injuryType(), "injuryType");
        String severity = Vocab.normalize(Vocab.SEVERITIES, request.severity(), "severity");
        return repository.insert(UuidV7.next(), tenantId, claimId, request.claimantPartyId(), injuryType,
                Vocab.optionalText(request.bodyPart(), 80), Vocab.diagnosisCodes(request.diagnosisCodes()),
                Vocab.optionalText(request.description(), 4000), severity, request.diagnosisDate(),
                Instant.now(clock));
    }

    public ClaimInjury update(UUID tenantId, UUID claimId, UUID injuryId, InjuryRequest request) {
        Claim claim = claims.require(tenantId, claimId);
        ClaimInjury existing = repository.findById(tenantId, claimId, injuryId)
                .orElseThrow(() -> RecordNotFoundException.of("Injury", injuryId));
        int expectedVersion = requireExpectedVersion(request.expectedVersion());
        if (expectedVersion != existing.version()) {
            throw new ClaimConflictException("Injury " + injuryId + " version " + expectedVersion
                    + " does not match current version " + existing.version());
        }
        UUID claimant = request.claimantPartyId() == null
                ? existing.claimantPartyId() : request.claimantPartyId();
        claims.requireClaimant(claim, claimant);
        String injuryType = request.injuryType() == null
                ? existing.injuryType()
                : Vocab.normalize(Vocab.INJURY_TYPES, request.injuryType(), "injuryType");
        String severity = request.severity() == null
                ? existing.severity()
                : Vocab.normalize(Vocab.SEVERITIES, request.severity(), "severity");
        List<String> codes = request.diagnosisCodes() == null
                ? existing.diagnosisCodes()
                : Vocab.diagnosisCodes(request.diagnosisCodes());
        String bodyPart = request.bodyPart() == null
                ? existing.bodyPart() : Vocab.optionalText(request.bodyPart(), 80);
        String description = request.description() == null
                ? existing.description() : Vocab.optionalText(request.description(), 4000);
        LocalDate diagnosisDate = request.diagnosisDate() == null
                ? existing.diagnosisDate() : request.diagnosisDate();
        return repository.update(tenantId, claimId, injuryId, claimant, injuryType, bodyPart, codes,
                description, severity, diagnosisDate, expectedVersion, Instant.now(clock));
    }

    private static int requireExpectedVersion(Integer expectedVersion) {
        if (expectedVersion == null) {
            throw new ClaimValidationException("expectedVersion is required");
        }
        return expectedVersion;
    }
}
