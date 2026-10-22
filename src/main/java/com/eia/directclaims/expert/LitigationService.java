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
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class LitigationService {
    private final LitigationRepository repository;
    private final ClaimRepository claimRepository;
    private final ClaimLookup claims;
    private final OutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public LitigationService(LitigationRepository repository,
                             ClaimRepository claimRepository,
                             ClaimLookup claims,
                             OutboxRepository outbox) {
        this(repository, claimRepository, claims, outbox, Clock.systemUTC());
    }

    LitigationService(LitigationRepository repository,
                      ClaimRepository claimRepository,
                      ClaimLookup claims,
                      OutboxRepository outbox,
                      Clock clock) {
        this.repository = repository;
        this.claimRepository = claimRepository;
        this.claims = claims;
        this.outbox = outbox;
        this.clock = clock;
    }

    public List<LitigationMatter> list(UUID tenantId, UUID claimId) {
        claims.require(tenantId, claimId);
        return repository.findAll(tenantId, claimId);
    }

    /**
     * Records a matter and flags the claim as in litigation. The claim version
     * is bumped in the same transaction as the matter and its
     * {@code litigation-created} event, so the per-claim event stream stays
     * monotonic. Damages are optional, but amount and currency must be supplied
     * together and the amount must be greater than zero.
     */
    @Transactional
    public LitigationMatter add(UUID tenantId, UUID claimId, LitigationRequest request) {
        Claim claim = claims.require(tenantId, claimId);
        String matterType = Vocab.normalize(Vocab.MATTER_TYPES, request.matterType(), "matterType");
        String jurisdiction = Vocab.requiredText(request.jurisdiction(), "jurisdiction", 120);
        UUID counsel = Vocab.requiredId(request.counselPartyId(), "counselPartyId");
        LocalDate filingDate = Vocab.requiredDate(request.filingDate(), "filingDate");
        validateDates(filingDate, request.trialDate());
        String status = Vocab.normalize(Vocab.LITIGATION_STATUSES, request.status(), "status");
        Damages damages = validateDamages(request.damagesSought(), request.damagesCurrency());
        Instant now = Instant.now(clock);
        int claimVersion = claimRepository.markLitigation(tenantId, claimId, claim.version(), now);
        LitigationMatter matter = repository.insert(UuidV7.next(), tenantId, claimId, matterType,
                jurisdiction, counsel, filingDate, request.trialDate(), status,
                Vocab.optionalText(request.caseNumber(), 64), damages.amount(), damages.currency(), now);
        outbox.insert(OutboxEvent.fromEnvelope(
                ClaimEvents.litigationCreated(claim, matter, claimVersion, now)));
        return matter;
    }

    /**
     * Updates a matter. A PATCH that omits both damages fields keeps the
     * recorded damages; supplying one without the other is rejected, and a
     * supplied amount must be greater than zero. The claim version is bumped in
     * the same transaction so the update event stays ordered with the claim.
     */
    @Transactional
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
        Damages damages = request.damagesSought() == null && isBlank(request.damagesCurrency())
                ? new Damages(existing.damagesSought(), existing.damagesCurrency())
                : validateDamages(request.damagesSought(), request.damagesCurrency());
        Instant now = Instant.now(clock);
        int claimVersion = claimRepository.bumpVersion(tenantId, claimId, claim.version(), now);
        LitigationMatter matter = repository.update(tenantId, claimId, litigationId, matterType,
                jurisdiction, counsel, filingDate, trialDate, status,
                request.caseNumber() == null
                        ? existing.caseNumber() : Vocab.optionalText(request.caseNumber(), 64),
                damages.amount(), damages.currency(), expectedVersion, now);
        outbox.insert(OutboxEvent.fromEnvelope(
                ClaimEvents.litigationUpdated(claim, matter, claimVersion, now)));
        return matter;
    }

    private static Damages validateDamages(BigDecimal rawAmount, String rawCurrency) {
        boolean hasAmount = rawAmount != null;
        boolean hasCurrency = !isBlank(rawCurrency);
        if (hasAmount != hasCurrency) {
            throw new ClaimValidationException(
                    "damagesSought and damagesCurrency must be provided together");
        }
        if (!hasAmount) {
            return new Damages(null, null);
        }
        BigDecimal amount = rawAmount.setScale(4, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            throw new ClaimValidationException("damagesSought must be greater than zero");
        }
        String currency = rawCurrency.trim().toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) {
            throw new ClaimValidationException(
                    "damagesCurrency must be a three-letter ISO currency code");
        }
        return new Damages(amount, currency);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void validateDates(LocalDate filingDate, LocalDate trialDate) {
        if (trialDate != null && trialDate.isBefore(filingDate)) {
            throw new ClaimValidationException("trialDate cannot be before filingDate");
        }
    }

    private record Damages(BigDecimal amount, String currency) {
    }
}
