package com.eia.directclaims.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class SettlementService {
    private final SettlementRepository repository;
    private final CoverageRepository coverageRepository;
    private final ClaimLookup claims;
    private final ClaimRepository claimRepository;
    private final OutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public SettlementService(SettlementRepository repository,
                             CoverageRepository coverageRepository,
                             ClaimLookup claims,
                             ClaimRepository claimRepository,
                             OutboxRepository outbox) {
        this(repository, coverageRepository, claims, claimRepository, outbox, Clock.systemUTC());
    }

    SettlementService(SettlementRepository repository,
                      CoverageRepository coverageRepository,
                      ClaimLookup claims,
                      ClaimRepository claimRepository,
                      OutboxRepository outbox,
                      Clock clock) {
        this.repository = repository;
        this.coverageRepository = coverageRepository;
        this.claims = claims;
        this.claimRepository = claimRepository;
        this.outbox = outbox;
        this.clock = clock;
    }

    public List<Settlement> list(UUID tenantId, UUID claimId) {
        claims.require(tenantId, claimId);
        return repository.findAll(tenantId, claimId);
    }

    public Settlement propose(UUID tenantId, UUID claimId, SettlementRequest request) {
        Claim claim = claims.require(tenantId, claimId);
        requireCoverageAllowsSettlement(claim);
        BigDecimal amount = Vocab.amount(request.amount(), "amount", false);
        String currency = Vocab.currency(request.currency(), claim.reserveCurrency());
        String structure = Vocab.normalize(Vocab.SETTLEMENT_STRUCTURES, request.structure(), "structure");
        String releaseScope = Vocab.optional(Vocab.RELEASE_SCOPES, request.releaseScope(), "releaseScope");
        String instructions = Vocab.optionalText(request.paymentInstructions(), 4000);
        String number = String.format("SET-%d-%07d", LocalDate.now(clock).getYear(),
                repository.nextSettlementSequence());
        return repository.insert(UuidV7.next(), tenantId, claimId, number, amount, currency, structure,
                releaseScope, instructions, Instant.now(clock));
    }

    /**
     * DIR-08: authorization cannot exceed the claim's settlement authority, and
     * the remaining authority already committed by other authorized or executed
     * settlements is deducted first.
     *
     * <p>Authorization bumps the claim version (as coverage and reserve writes
     * already do) so the claim's event aggregate version stays monotonic, then
     * appends the settlement event in the same transaction.</p>
     */
    @Transactional
    public Settlement approve(UUID tenantId,
                              UUID claimId,
                              UUID settlementId,
                              SettlementApprovalRequest request) {
        Claim claim = claims.require(tenantId, claimId);
        requireCoverageAllowsSettlement(claim);
        Settlement settlement = repository.findById(tenantId, claimId, settlementId)
                .orElseThrow(() -> RecordNotFoundException.of("Settlement", settlementId));
        if (!"PROPOSED".equals(settlement.status())) {
            throw new ClaimValidationException("Only a PROPOSED settlement can be authorized");
        }
        int expectedVersion = requireExpectedVersion(request.expectedVersion());
        if (expectedVersion != settlement.version()) {
            throw new ClaimConflictException("Settlement " + settlementId + " version " + expectedVersion
                    + " does not match current version " + settlement.version());
        }
        String approvalAuthority = Vocab.requiredText(
                request.approvalAuthority(), "approvalAuthority", 120);
        UUID authorizedBy = Vocab.requiredId(request.authorizedBy(), "authorizedBy");
        BigDecimal authority = claim.settlementAuthorityAmount();
        if (authority == null || authority.signum() <= 0) {
            throw new ClaimValidationException("The claim has no settlement authority");
        }
        BigDecimal committed = repository.committedAmount(tenantId, claimId, settlementId);
        BigDecimal remaining = authority.subtract(committed);
        if (settlement.amount().compareTo(remaining) > 0) {
            throw new ClaimValidationException("Settlement amount " + settlement.amount()
                    + " exceeds the remaining settlement authority " + remaining);
        }
        Instant now = Instant.now(clock);
        int claimVersion = claimRepository.bumpVersion(tenantId, claimId, claim.version(), now);
        Settlement authorized = repository.authorize(tenantId, claimId, settlementId,
                approvalAuthority, authorizedBy, expectedVersion, now);
        outbox.insert(OutboxEvent.fromEnvelope(
                ClaimEvents.settlementAuthorized(claim, authorized, claimVersion, now)));
        return authorized;
    }

    public Settlement execute(UUID tenantId,
                              UUID claimId,
                              UUID settlementId,
                              SettlementExecutionRequest request) {
        claims.require(tenantId, claimId);
        Settlement settlement = repository.findById(tenantId, claimId, settlementId)
                .orElseThrow(() -> RecordNotFoundException.of("Settlement", settlementId));
        if (!"AUTHORIZED".equals(settlement.status())) {
            throw new ClaimValidationException("Only an AUTHORIZED settlement can be executed");
        }
        int expectedVersion = requireExpectedVersion(request.expectedVersion());
        if (expectedVersion != settlement.version()) {
            throw new ClaimConflictException("Settlement " + settlementId + " version " + expectedVersion
                    + " does not match current version " + settlement.version());
        }
        UUID release = Vocab.requiredId(request.releaseDocumentId(), "releaseDocumentId");
        String instructions = request.paymentInstructions() == null
                ? settlement.paymentInstructions()
                : Vocab.optionalText(request.paymentInstructions(), 4000);
        if (instructions == null) {
            throw new ClaimValidationException("paymentInstructions are required before execution");
        }
        return repository.execute(tenantId, claimId, settlementId, release, instructions, expectedVersion,
                Instant.now(clock));
    }

    /**
     * A settlement requires a final coverage decision; a denied claim cannot be
     * settled (SPEC 11.4.7 DIR-08, 11.4.13 COV-01).
     */
    private void requireCoverageAllowsSettlement(Claim claim) {
        CoverageDecision decision = coverageRepository.findLatestFinal(claim.tenantId(), claim.id())
                .orElseThrow(() -> new ClaimValidationException(
                        "A final coverage decision is required before settlement"));
        if ("DENIED".equals(decision.position())) {
            throw new ClaimValidationException("A claim with a DENIED coverage position cannot be settled");
        }
    }

    private static int requireExpectedVersion(Integer expectedVersion) {
        if (expectedVersion == null) {
            throw new ClaimValidationException("expectedVersion is required");
        }
        return expectedVersion;
    }
}
