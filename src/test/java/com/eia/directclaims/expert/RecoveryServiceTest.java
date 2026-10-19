package com.eia.directclaims.expert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.eia.directclaims.expert.Fixtures.CLAIM_ID;
import static com.eia.directclaims.expert.Fixtures.RECOVERY_ID;
import static com.eia.directclaims.expert.Fixtures.TENANT;
import static com.eia.directclaims.expert.Fixtures.claim;
import static com.eia.directclaims.expert.Fixtures.recovery;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecoveryServiceTest {
    @Mock
    private RecoveryRepository repository;
    @Mock
    private SettlementRepository settlementRepository;
    @Mock
    private ClaimRepository claimRepository;

    private RecoveryService service;

    @BeforeEach
    void setUp() {
        service = new RecoveryService(repository, settlementRepository, new ClaimLookup(claimRepository),
                Clock.fixed(Instant.parse("2026-03-18T09:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void aReceivedRecoveryRequiresADate() {
        claimExists();

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.record(TENANT, CLAIM_ID,
                        request("RECEIVED", new BigDecimal("20000.00"), null)));

        assertTrue(error.getMessage().contains("recoveryDate"));
        verifyNoInteractions(repository);
    }

    @Test
    void aReceivedRecoveryCannotExceedPaidLoss() {
        claimExists();
        when(settlementRepository.paidLoss(TENANT, CLAIM_ID)).thenReturn(new BigDecimal("10000.0000"));
        when(repository.receivedTotal(TENANT, CLAIM_ID, null)).thenReturn(new BigDecimal("0.0000"));

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.record(TENANT, CLAIM_ID,
                        request("RECEIVED", new BigDecimal("20000.00"), LocalDate.of(2026, 5, 1))));

        assertTrue(error.getMessage().contains("cannot exceed paid loss"));
    }

    @Test
    void existingReceivedRecoveriesCountTowardThePaidLossCap() {
        claimExists();
        when(settlementRepository.paidLoss(TENANT, CLAIM_ID)).thenReturn(new BigDecimal("30000.0000"));
        when(repository.receivedTotal(TENANT, CLAIM_ID, null)).thenReturn(new BigDecimal("20000.0000"));

        assertThrows(ClaimValidationException.class, () -> service.record(TENANT, CLAIM_ID,
                request("RECEIVED", new BigDecimal("20000.00"), LocalDate.of(2026, 5, 1))));
    }

    @Test
    void aNonReceivedRecoveryDoesNotRequirePaidLoss() {
        claimExists();
        when(repository.insert(any(), eq(TENANT), eq(CLAIM_ID), eq("SUBROGATION"),
                eq(new BigDecimal("20000.0000")), eq("USD"), eq("Co-defendant Acme"), eq("PURSUING"),
                isNull(), any(Instant.class))).thenReturn(recovery("PURSUING"));

        Recovery recorded = service.record(TENANT, CLAIM_ID,
                request("PURSUING", new BigDecimal("20000.00"), null));

        assertEquals("PURSUING", recorded.status());
        verifyNoInteractions(settlementRepository);
    }

    @Test
    void recordsAReceivedRecoveryWithinPaidLoss() {
        claimExists();
        when(settlementRepository.paidLoss(TENANT, CLAIM_ID)).thenReturn(new BigDecimal("60000.0000"));
        when(repository.receivedTotal(TENANT, CLAIM_ID, null)).thenReturn(new BigDecimal("0.0000"));
        when(repository.insert(any(), eq(TENANT), eq(CLAIM_ID), eq("SUBROGATION"),
                eq(new BigDecimal("20000.0000")), eq("USD"), eq("Co-defendant Acme"), eq("RECEIVED"),
                eq(LocalDate.of(2026, 5, 1)), any(Instant.class))).thenReturn(recovery("RECEIVED"));

        Recovery recorded = service.record(TENANT, CLAIM_ID,
                request("RECEIVED", new BigDecimal("20000.00"), LocalDate.of(2026, 5, 1)));

        assertEquals("RECEIVED", recorded.status());
    }

    @Test
    void advancingToReceivedRevalidatesTheCap() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, RECOVERY_ID))
                .thenReturn(Optional.of(recovery("PURSUING")));
        when(settlementRepository.paidLoss(TENANT, CLAIM_ID)).thenReturn(new BigDecimal("10000.0000"));
        when(repository.receivedTotal(TENANT, CLAIM_ID, RECOVERY_ID))
                .thenReturn(new BigDecimal("0.0000"));

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.changeStatus(TENANT, CLAIM_ID, RECOVERY_ID,
                        new RecoveryRequest(null, null, null, null, "RECEIVED",
                                LocalDate.of(2026, 5, 1), 1)));

        assertTrue(error.getMessage().contains("cannot exceed paid loss"));
    }

    @Test
    void advancingToReceivedRejectsAStaleVersion() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, RECOVERY_ID))
                .thenReturn(Optional.of(recovery("PURSUING")));

        assertThrows(ClaimConflictException.class, () -> service.changeStatus(TENANT, CLAIM_ID,
                RECOVERY_ID, new RecoveryRequest(null, null, null, null, "RECEIVED",
                        LocalDate.of(2026, 5, 1), 4)));
    }

    @Test
    void rejectsAnUnknownRecoveryType() {
        claimExists();
        RecoveryRequest request = new RecoveryRequest("MAGIC", new BigDecimal("100.00"), "USD",
                "Someone", "PURSUING", null, null);

        assertThrows(ClaimValidationException.class, () -> service.record(TENANT, CLAIM_ID, request));
    }

    private void claimExists() {
        when(claimRepository.findById(TENANT, CLAIM_ID)).thenReturn(Optional.of(claim("REGISTERED", 0)));
    }

    private static RecoveryRequest request(String status, BigDecimal amount, LocalDate date) {
        return new RecoveryRequest("SUBROGATION", amount, "USD", "Co-defendant Acme", status, date, null);
    }
}

