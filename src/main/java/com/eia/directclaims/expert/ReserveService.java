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
public class ReserveService {
    private static final BigDecimal ZERO = new BigDecimal("0.0000");

    private final ReserveRepository repository;
    private final ClaimLookup claims;
    private final OutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public ReserveService(ReserveRepository repository, ClaimLookup claims, OutboxRepository outbox) {
        this(repository, claims, outbox, Clock.systemUTC());
    }

    ReserveService(ReserveRepository repository, ClaimLookup claims, OutboxRepository outbox,
                   Clock clock) {
        this.repository = repository;
        this.claims = claims;
        this.outbox = outbox;
        this.clock = clock;
    }

    public ReserveSummary summary(UUID tenantId, UUID claimId) {
        Claim claim = claims.require(tenantId, claimId);
        List<ReserveHistoryEntry> history = repository.findHistory(tenantId, claimId);
        List<ReserveHistoryEntry> current = repository.findCurrent(tenantId, claimId);
        BigDecimal total = current.stream()
                .map(ReserveHistoryEntry::amount)
                .reduce(new BigDecimal("0.0000"), BigDecimal::add);
        List<ReserveSummary.ReserveComponentBalance> components = current.stream()
                .map(entry -> new ReserveSummary.ReserveComponentBalance(
                        entry.component(), entry.amount(), entry.currency()))
                .toList();
        return new ReserveSummary(claimId, claim.reserveCurrency(), total.setScale(4), components, history);
    }

    /**
     * Establishes or changes one component. The request amount is the new
     * component amount, not a delta; the previous current row is closed, the
     * new row appended, and the event written in one transaction.
     */
    @Transactional
    public ReserveHistoryEntry change(UUID tenantId, UUID claimId, ReserveChangeRequest request) {
        Claim claim = claims.require(tenantId, claimId);
        String component = Vocab.normalize(Vocab.RESERVE_COMPONENTS, request.component(), "component");
        BigDecimal amount = Vocab.amount(request.amount(), "amount", true);
        String reason = Vocab.normalize(Vocab.RESERVE_REASONS, request.reasonCode(), "reasonCode");
        int expectedVersion = requireExpectedVersion(request.expectedVersion());
        if (expectedVersion != claim.version()) {
            throw new ClaimConflictException("Claim " + claimId + " version " + expectedVersion
                    + " does not match current version " + claim.version());
        }
        String currency = Vocab.currency(request.currency(), claim.reserveCurrency());
        LocalDate effectiveDate = request.effectiveDate() == null
                ? LocalDate.now(clock) : request.effectiveDate();
        String authority = Vocab.optionalText(request.authority(), 120);
        if (requiresAuthority(claim, amount) && authority == null) {
            throw new ClaimValidationException(
                    "authority is required when a reserve exceeds the claim settlement authority");
        }
        List<ReserveHistoryEntry> current = repository.findCurrent(tenantId, claimId);
        BigDecimal previousAmount = current.stream()
                .filter(entry -> component.equals(entry.component()))
                .map(ReserveHistoryEntry::amount)
                .findFirst()
                .orElse(ZERO);
        BigDecimal totalBefore = current.stream()
                .map(ReserveHistoryEntry::amount)
                .reduce(ZERO, BigDecimal::add);
        Instant now = Instant.now(clock);
        ReserveHistoryEntry entry = repository.postChange(claim, component, amount, currency,
                effectiveDate, reason, Vocab.optionalText(request.reasonText(), 4000), authority,
                expectedVersion, now);
        BigDecimal totalAfter = totalBefore.subtract(previousAmount).add(amount);
        outbox.insert(OutboxEvent.fromEnvelope(
                ClaimEvents.reserveChanged(claim, entry, previousAmount, totalAfter, now)));
        return entry;
    }

    private static boolean requiresAuthority(Claim claim, BigDecimal amount) {
        BigDecimal limit = claim.settlementAuthorityAmount();
        return limit == null || amount.compareTo(limit) > 0;
    }

    private static int requireExpectedVersion(Integer expectedVersion) {
        if (expectedVersion == null) {
            throw new ClaimValidationException("expectedVersion is required");
        }
        return expectedVersion;
    }
}
