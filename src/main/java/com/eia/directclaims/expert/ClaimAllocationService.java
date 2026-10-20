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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Claim allocation commands and queries.
 *
 * <p>An allocation splits a claim across a policy year and layer. The policy
 * must be the claim's own policy, the year must be a realistic calendar year,
 * the percentage must be in (0, 1], and the percentages across one claim may
 * not exceed 1. The allocated amount has no reserve cap. A create bumps the
 * parent claim version and appends {@code direct.claim.allocation-created} to
 * the outbox in the same transaction, so a rollback leaves neither the row nor
 * the event behind.</p>
 *
 * <p>An allocation may optionally reference the policy limit it erodes and
 * starts in erosion state {@code NOT_POSTED}. Recording the posting outcome is
 * a separate command: {@code POSTED} stamps the posting instant and
 * {@code FAILED} clears it so a failed posting can be retried. A posting is
 * terminal once {@code POSTED}; repeating the current status is an idempotent
 * no-op, while changing a posted allocation is a conflict.</p>
 */
@Service
public class ClaimAllocationService {
    static final int MIN_POLICY_YEAR = 1900;
    static final int MAX_POLICY_YEAR = 2200;
    static final int MAX_LAYER_LENGTH = 32;
    static final int MAX_EROSION_MESSAGE_LENGTH = 4000;

    static final String EROSION_POSTED = "POSTED";

    private final ClaimAllocationRepository repository;
    private final ClaimRepository claimRepository;
    private final ClaimLookup claims;
    private final OutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public ClaimAllocationService(ClaimAllocationRepository repository,
                                  ClaimRepository claimRepository,
                                  ClaimLookup claims,
                                  OutboxRepository outbox) {
        this(repository, claimRepository, claims, outbox, Clock.systemUTC());
    }

    ClaimAllocationService(ClaimAllocationRepository repository,
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

    public ClaimAllocationSummary list(UUID tenantId, UUID claimId) {
        claims.require(tenantId, claimId);
        List<ClaimAllocation> items = repository.findAll(tenantId, claimId);
        BigDecimal totalPct = items.stream()
                .map(ClaimAllocation::allocationPct)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(6, RoundingMode.HALF_UP);
        BigDecimal totalAmount = items.stream()
                .map(ClaimAllocation::allocatedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_UP);
        return new ClaimAllocationSummary(items, totalPct, totalAmount);
    }

    @Transactional
    public ClaimAllocation create(UUID tenantId,
                                  UUID claimId,
                                  CreateClaimAllocationRequest request) {
        Claim claim = claims.require(tenantId, claimId);
        UUID policyId = requireClaimPolicy(claim, request.policyId());
        int policyYear = requirePolicyYear(request.policyYear());
        String layer = Vocab.requiredText(request.layer(), "layer", MAX_LAYER_LENGTH);
        BigDecimal allocationPct = requireAllocationPct(request.allocationPct());
        BigDecimal allocatedAmount = Vocab.amount(
                request.allocatedAmount(), "allocatedAmount", true);

        BigDecimal candidateTotal = repository.totalPct(tenantId, claimId)
                .add(allocationPct)
                .setScale(6, RoundingMode.HALF_UP);
        if (candidateTotal.compareTo(BigDecimal.ONE) > 0) {
            throw new AllocationExceedsHundredException(
                    "Allocations for claim " + claim.claimNumber() + " would total "
                            + candidateTotal.toPlainString() + ", which exceeds 1");
        }

        Instant now = Instant.now(clock);
        int claimVersion = claimRepository.bumpVersion(tenantId, claimId, claim.version(), now);
        ClaimAllocation allocation = repository.insert(UuidV7.next(), tenantId, claimId, policyId,
                request.limitId(), policyYear, layer, allocationPct, allocatedAmount, now);
        ClaimAllocationRepository.Totals totals = repository.totals(tenantId, claimId);
        outbox.insert(OutboxEvent.fromEnvelope(ClaimEvents.allocationCreated(
                claim, allocation, claimVersion, totals.pct(), totals.amount(), now)));
        return allocation;
    }

    /**
     * Records the policy-erosion posting outcome of one allocation. The claim
     * lookup and the allocation lookup both carry the tenant, so a foreign
     * tenant or another claim's allocation is 404. {@code POSTED} stamps the
     * posting instant; {@code FAILED} stores the message with a null instant and
     * leaves a later successful retry possible. Once posted the state is
     * terminal: the same status is an idempotent no-op that leaves the stored
     * message and instant untouched, while a different status is 409.
     */
    @Transactional
    public ClaimAllocation updateErosion(UUID tenantId,
                                         UUID claimId,
                                         UUID allocationId,
                                         AllocationErosionRequest request) {
        claims.require(tenantId, claimId);
        ClaimAllocation current = repository.findById(tenantId, claimId, allocationId)
                .orElseThrow(() -> RecordNotFoundException.of("Claim allocation", allocationId));
        String status = Vocab.normalize(Vocab.EROSION_STATUSES, request.status(), "status");
        String message = Vocab.optionalText(request.message(), MAX_EROSION_MESSAGE_LENGTH);
        if (status.equals(current.erosionStatus())) {
            return current;
        }
        if (EROSION_POSTED.equals(current.erosionStatus())) {
            throw postedConflict(allocationId, status);
        }

        Instant postedAt = EROSION_POSTED.equals(status) ? Instant.now(clock) : null;
        Optional<ClaimAllocation> updated = repository.updateErosion(
                tenantId, claimId, allocationId, status, message, postedAt);
        if (updated.isPresent()) {
            return updated.get();
        }

        // A concurrent writer posted the allocation between the read and the
        // update. Re-read: the winner's same status is an idempotent no-op,
        // anything else is the terminal-posted conflict.
        ClaimAllocation latest = repository.findById(tenantId, claimId, allocationId)
                .orElseThrow(() -> RecordNotFoundException.of("Claim allocation", allocationId));
        if (status.equals(latest.erosionStatus())) {
            return latest;
        }
        throw postedConflict(allocationId, status);
    }

    private static ClaimConflictException postedConflict(UUID allocationId, String status) {
        return new ClaimConflictException("Claim allocation " + allocationId
                + " is already POSTED and cannot be changed to " + status);
    }

    private static UUID requireClaimPolicy(Claim claim, UUID policyId) {
        if (policyId == null || !policyId.equals(claim.policyId())) {
            throw new PolicyMismatchException("policyId " + policyId
                    + " does not match the claim policy " + claim.policyId());
        }
        return policyId;
    }

    private static int requirePolicyYear(Integer policyYear) {
        if (policyYear == null) {
            throw new ClaimValidationException("policyYear is required");
        }
        if (policyYear < MIN_POLICY_YEAR || policyYear > MAX_POLICY_YEAR) {
            throw new ClaimValidationException("policyYear must be between "
                    + MIN_POLICY_YEAR + " and " + MAX_POLICY_YEAR);
        }
        return policyYear;
    }

    private static BigDecimal requireAllocationPct(BigDecimal allocationPct) {
        if (allocationPct == null) {
            throw new ClaimValidationException("allocationPct is required");
        }
        BigDecimal scaled = allocationPct.setScale(6, RoundingMode.HALF_UP);
        if (allocationPct.signum() <= 0 || allocationPct.compareTo(BigDecimal.ONE) > 0
                || scaled.signum() <= 0 || scaled.compareTo(BigDecimal.ONE) > 0) {
            throw new ClaimValidationException(
                    "allocationPct must be greater than 0 and at most 1");
        }
        return scaled;
    }
}
