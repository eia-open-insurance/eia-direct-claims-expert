package com.eia.directclaims.expert;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ClaimService {
    private final ClaimRepository repository;
    private final Clock clock;

    @Autowired
    public ClaimService(ClaimRepository repository) {
        this(repository, Clock.systemUTC());
    }

    ClaimService(ClaimRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<Claim> list(UUID tenantId, String statusFilter) {
        ClaimStatus status = statusFilter == null || statusFilter.isBlank()
                ? null
                : ClaimStatus.from(statusFilter);
        return repository.findAll(tenantId, status);
    }

    public Claim get(UUID tenantId, UUID id) {
        return repository.findById(tenantId, id).orElseThrow(() -> new ClaimNotFoundException(id));
    }

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

        String causeCode = request.causeCode() == null || request.causeCode().isBlank()
                ? null
                : request.causeCode().trim();

        Instant now = Instant.now(clock);
        long sequence = repository.nextClaimSequence();
        String claimNumber = String.format("CLM-%d-%07d", request.reportDate().getYear(), sequence);

        return repository.insert(UuidV7.next(), claimNumber, request.tenantId(), request.policyId(),
                request.claimantPartyId(), initial, request.lossDate(), request.reportDate(), causeCode,
                reserve, currency, now);
    }

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

        int expected = expectedVersion == null ? current.version() : expectedVersion;
        if (expected != current.version()) {
            throw new ClaimConflictException("Claim version " + expected
                    + " does not match current version " + current.version());
        }
        return repository.updateStatus(tenantId, id, next, expected, Instant.now(clock));
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
