package com.eia.directclaims.expert;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class RecoveryService {
    private final RecoveryRepository repository;
    private final SettlementRepository settlementRepository;
    private final ClaimLookup claims;
    private final Clock clock;

    @Autowired
    public RecoveryService(RecoveryRepository repository,
                           SettlementRepository settlementRepository,
                           ClaimLookup claims) {
        this(repository, settlementRepository, claims, Clock.systemUTC());
    }

    RecoveryService(RecoveryRepository repository,
                    SettlementRepository settlementRepository,
                    ClaimLookup claims,
                    Clock clock) {
        this.repository = repository;
        this.settlementRepository = settlementRepository;
        this.claims = claims;
        this.clock = clock;
    }

    public List<Recovery> list(UUID tenantId, UUID claimId) {
        claims.require(tenantId, claimId);
        return repository.findAll(tenantId, claimId);
    }

    public Recovery record(UUID tenantId, UUID claimId, RecoveryRequest request) {
        claims.require(tenantId, claimId);
        String type = Vocab.normalize(Vocab.RECOVERY_TYPES, request.recoveryType(), "recoveryType");
        BigDecimal amount = Vocab.amount(request.amount(), "amount", false);
        String status = Vocab.normalize(Vocab.RECOVERY_STATUSES, request.status(), "status");
        LocalDate date = request.recoveryDate();
        if ("RECEIVED".equals(status)) {
            if (date == null) {
                throw new ClaimValidationException("recoveryDate is required for a RECEIVED recovery");
            }
            validateAgainstPaidLoss(tenantId, claimId, amount, null);
        }
        return repository.insert(UuidV7.next(), tenantId, claimId, type, amount,
                Vocab.currency(request.currency(), "USD"),
                Vocab.requiredText(request.source(), "source", 160), status, date, Instant.now(clock));
    }

    public Recovery changeStatus(UUID tenantId, UUID claimId, UUID recoveryId, RecoveryRequest request) {
        claims.require(tenantId, claimId);
        Recovery existing = repository.findById(tenantId, claimId, recoveryId)
                .orElseThrow(() -> RecordNotFoundException.of("Recovery", recoveryId));
        int expectedVersion = requireExpectedVersion(request.expectedVersion());
        if (expectedVersion != existing.version()) {
            throw new ClaimConflictException("Recovery " + recoveryId + " version " + expectedVersion
                    + " does not match current version " + existing.version());
        }
        String status = request.status() == null
                ? existing.status() : Vocab.normalize(Vocab.RECOVERY_STATUSES, request.status(), "status");
        LocalDate date = request.recoveryDate() == null ? existing.recoveryDate() : request.recoveryDate();
        if ("RECEIVED".equals(status)) {
            if (date == null) {
                throw new ClaimValidationException("recoveryDate is required for a RECEIVED recovery");
            }
            validateAgainstPaidLoss(tenantId, claimId, existing.amount(), recoveryId);
        }
        return repository.updateStatus(tenantId, claimId, recoveryId, status, date, expectedVersion,
                Instant.now(clock));
    }

    /**
     * Received recoveries cannot exceed paid loss. Paid loss is the sum of
     * executed settlements for the claim.
     */
    private void validateAgainstPaidLoss(UUID tenantId, UUID claimId, BigDecimal amount, UUID excludedId) {
        BigDecimal paidLoss = settlementRepository.paidLoss(tenantId, claimId);
        BigDecimal received = repository.receivedTotal(tenantId, claimId, excludedId);
        if (received.add(amount).compareTo(paidLoss) > 0) {
            throw new ClaimValidationException("Received recoveries " + received.add(amount)
                    + " cannot exceed paid loss " + paidLoss);
        }
    }

    private static int requireExpectedVersion(Integer expectedVersion) {
        if (expectedVersion == null) {
            throw new ClaimValidationException("expectedVersion is required");
        }
        return expectedVersion;
    }
}
