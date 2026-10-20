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
import java.util.Objects;
import java.util.UUID;

@Service
public class ClaimService {
    private final ClaimRepository repository;
    private final CoverageRepository coverageRepository;
    private final ReserveRepository reserveRepository;
    private final LienRepository lienRepository;
    private final OutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public ClaimService(ClaimRepository repository,
                        CoverageRepository coverageRepository,
                        ReserveRepository reserveRepository,
                        LienRepository lienRepository,
                        OutboxRepository outbox) {
        this(repository, coverageRepository, reserveRepository, lienRepository, outbox,
                Clock.systemUTC());
    }

    ClaimService(ClaimRepository repository,
                 CoverageRepository coverageRepository,
                 ReserveRepository reserveRepository,
                 LienRepository lienRepository,
                 OutboxRepository outbox,
                 Clock clock) {
        this.repository = repository;
        this.coverageRepository = coverageRepository;
        this.reserveRepository = reserveRepository;
        this.lienRepository = lienRepository;
        this.outbox = outbox;
        this.clock = clock;
    }

    public List<Claim> list(UUID tenantId, String statusFilter) {
        return list(tenantId, statusFilter, null);
    }

    public List<Claim> list(UUID tenantId, String statusFilter, UUID claimantPartyId) {
        ClaimStatus status = statusFilter == null || statusFilter.isBlank()
                ? null
                : ClaimStatus.from(statusFilter);
        return repository.findAll(tenantId, status, claimantPartyId);
    }

    public Claim get(UUID tenantId, UUID id) {
        return repository.findById(tenantId, id).orElseThrow(() -> new ClaimNotFoundException(id));
    }

    @Transactional
    public Claim create(CreateClaimRequest request) {
        requireDates(request);
        ClaimStatus initial = request.status() == null || request.status().isBlank()
                ? ClaimStatus.REGISTERED
                : ClaimStatus.from(request.status());
        if (!initial.isInitial()) {
            throw new ClaimValidationException("A claim must be created in DRAFT or REGISTERED status");
        }

        BigDecimal reserve = request.reserveAmount() == null
                ? new BigDecimal("0.0000")
                : request.reserveAmount().setScale(4, RoundingMode.HALF_UP);
        if (reserve.signum() < 0) {
            throw new ClaimValidationException("reserveAmount must be greater than or equal to 0");
        }

        String currency = request.reserveCurrency() == null || request.reserveCurrency().isBlank()
                ? "USD"
                : request.reserveCurrency().trim().toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) {
            throw new ClaimValidationException("reserveCurrency must be a three-letter ISO currency code");
        }

        String claimType = request.claimType() == null || request.claimType().isBlank()
                ? "BODILY_INJURY"
                : Vocab.normalize(Vocab.CLAIM_TYPES, request.claimType(), "claimType");

        BigDecimal authority = request.settlementAuthorityAmount() == null
                ? null
                : request.settlementAuthorityAmount().setScale(4, RoundingMode.HALF_UP);
        if (authority != null && authority.signum() < 0) {
            throw new ClaimValidationException("settlementAuthorityAmount must be greater than or equal to 0");
        }
        String authorityCurrency = authority == null
                ? null
                : Vocab.currency(request.settlementAuthorityCurrency(), currency);

        String causeCode = request.causeCode() == null || request.causeCode().isBlank()
                ? null
                : request.causeCode().trim();

        Instant now = Instant.now(clock);
        long sequence = repository.nextClaimSequence();
        String claimNumber = String.format("CLM-%d-%07d", request.reportDate().getYear(), sequence);

        Claim created = repository.insert(UuidV7.next(), claimNumber, request.tenantId(), request.policyId(),
                request.claimantPartyId(), claimType, initial, request.lossDate(), request.reportDate(),
                causeCode, reserve, currency, authority, authorityCurrency, now);
        reserveRepository.seedInitial(request.tenantId(), created.id(), reserve, currency, now);
        Claim registered = repository.findById(request.tenantId(), created.id())
                .orElseThrow(() -> new ClaimNotFoundException(created.id()));
        outbox.insert(OutboxEvent.fromEnvelope(ClaimEvents.registered(registered, now)));
        return registered;
    }

    @Transactional
    public Claim changeStatus(UUID tenantId, UUID id, String rawStatus, Integer expectedVersion) {
        Claim current = get(tenantId, id);
        ClaimStatus next = ClaimStatus.from(rawStatus);
        ClaimStatus currentStatus = ClaimStatus.from(current.status());

        if (currentStatus == next) {
            throw new ClaimValidationException("Claim is already in status " + next);
        }
        if (!currentStatus.canTransitionTo(next)) {
            throw new ClaimValidationException(
                    "Illegal claim status transition " + currentStatus + " -> " + next);
        }
        if (next == ClaimStatus.OPEN) {
            requireCoverageBeforeOpen(tenantId, id);
        }

        int expected = expectedVersion == null ? current.version() : expectedVersion;
        if (expected != current.version()) {
            throw new ClaimConflictException("Claim version " + expected
                    + " does not match current version " + current.version());
        }
        Instant now = Instant.now(clock);
        Claim after = repository.updateStatus(tenantId, id, next, expected, now);
        outbox.insert(OutboxEvent.fromEnvelope(next == ClaimStatus.CLOSED
                ? ClaimEvents.closed(current, after, now)
                : ClaimEvents.statusUpdated(current, after, now)));
        return after;
    }

    /**
     * Assigns or reassigns a claim. The command is a no-op when the normalized
     * adjuster and team already match and the caller's version is current; a
     * stale version is 409 and an unknown or non-DIRECT claim is 404. A real
     * change bumps the claim version and writes one outbox event in the same
     * transaction.
     */
    @Transactional
    public Claim assign(UUID tenantId, UUID claimId, ClaimAssignmentRequest request, String rawActor) {
        String assignedAdjuster = Vocab.requiredText(
                request.assignedAdjuster(), "assignedAdjuster", 128);
        String assignedTeam = Vocab.optionalText(request.assignedTeam(), 64);
        String actor = Vocab.optionalText(rawActor, 128);
        if (actor == null) {
            actor = assignedAdjuster;
        }
        if (request.expectedVersion() == null) {
            throw new ClaimValidationException("expectedVersion is required");
        }
        if (request.expectedVersion() < 0) {
            throw new ClaimValidationException(
                    "expectedVersion must be greater than or equal to 0");
        }

        Claim current = get(tenantId, claimId);
        int expectedVersion = request.expectedVersion();
        if (expectedVersion != current.version()) {
            throw new ClaimConflictException("Claim version " + expectedVersion
                    + " does not match current version " + current.version());
        }
        if (assignedAdjuster.equals(current.assignedAdjuster())
                && Objects.equals(assignedTeam, current.assignedTeam())) {
            return current;
        }

        Instant now = Instant.now(clock);
        Claim after = repository.assign(tenantId, claimId, assignedAdjuster, assignedTeam,
                actor, expectedVersion, now);
        outbox.insert(OutboxEvent.fromEnvelope(ClaimEvents.assigned(after, now)));
        return after;
    }

    /**
     * DIR-03/DIR-11 style closure preconditions: no unresolved lien, no open
     * reserve, and a legal transition to CLOSED.
     */
    @Transactional
    public Claim close(UUID tenantId, UUID id, Integer expectedVersion) {
        Claim current = get(tenantId, id);
        long unresolvedLiens = lienRepository.countUnresolved(tenantId, id);
        if (unresolvedLiens > 0) {
            throw new ClaimValidationException("Claim cannot close while " + unresolvedLiens
                    + " lien(s) are unresolved");
        }
        if (reserveRepository.currentTotal(tenantId, id).signum() > 0) {
            throw new ClaimValidationException("Claim cannot close while an open reserve exists");
        }
        int expected = expectedVersion == null ? current.version() : expectedVersion;
        if (expected != current.version()) {
            throw new ClaimConflictException("Claim version " + expected
                    + " does not match current version " + current.version());
        }
        ClaimStatus currentStatus = ClaimStatus.from(current.status());
        if (!currentStatus.canTransitionTo(ClaimStatus.CLOSED)) {
            throw new ClaimValidationException(
                    "Claim status " + currentStatus + " cannot move to CLOSED");
        }
        Instant now = Instant.now(clock);
        Claim after = repository.updateStatus(tenantId, id, ClaimStatus.CLOSED, expected, now);
        outbox.insert(OutboxEvent.fromEnvelope(ClaimEvents.closed(current, after, now)));
        return after;
    }

    private void requireCoverageBeforeOpen(UUID tenantId, UUID id) {
        CoverageDecision decision = coverageRepository.findLatestFinal(tenantId, id).orElse(null);
        if (decision == null) {
            throw new ClaimValidationException("A final coverage decision is required before OPEN");
        }
        if ("DENIED".equals(decision.position())) {
            throw new ClaimValidationException(
                    "A claim with a DENIED coverage position cannot move to OPEN");
        }
    }

    private static void requireDates(CreateClaimRequest request) {
        LocalDate lossDate = request.lossDate();
        LocalDate reportDate = request.reportDate();
        if (lossDate == null || reportDate == null) {
            throw new ClaimValidationException("lossDate and reportDate are required");
        }
        if (reportDate.isBefore(lossDate)) {
            throw new ClaimValidationException("reportDate must be on or after lossDate");
        }
    }
}
