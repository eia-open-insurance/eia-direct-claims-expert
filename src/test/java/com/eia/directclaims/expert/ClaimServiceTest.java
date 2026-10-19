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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID CLAIM_ID = UUID.fromString("0192f3a4-2b7e-7d1f-a9c4-3e8b5f0c9d21");
    private static final UUID POLICY_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID CLAIMANT_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final LocalDate LOSS_DATE = LocalDate.of(2026, 3, 14);
    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 3, 18);

    @Mock
    private ClaimRepository repository;

    private ClaimService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-03-18T09:00:00Z"), ZoneOffset.UTC);
        service = new ClaimService(repository, clock);
    }

    @Test
    void rejectsReportDateBeforeLossDate() {
        CreateClaimRequest request = request(LOSS_DATE.minusDays(1), new BigDecimal("100.00"), "USD", null);

        ClaimValidationException error = assertThrows(ClaimValidationException.class, () -> service.create(request));

        assertEquals("reportDate must be on or after lossDate", error.getMessage());
        verifyNoInteractions(repository);
    }

    @Test
    void acceptsReportDateEqualToLossDate() {
        when(repository.nextClaimSequence()).thenReturn(1L);
        when(repository.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(claim("REGISTERED", 0));

        Claim created = service.create(request(LOSS_DATE, new BigDecimal("0.00"), "USD", null));

        assertEquals("REGISTERED", created.status());
    }

    @Test
    void rejectsNegativeReserve() {
        CreateClaimRequest request = request(REPORT_DATE, new BigDecimal("-0.01"), "USD", null);

        assertThrows(ClaimValidationException.class, () -> service.create(request));
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsNonInitialStatusOnCreate() {
        CreateClaimRequest request = request(REPORT_DATE, new BigDecimal("100.00"), "USD", "PAID");

        assertThrows(ClaimValidationException.class, () -> service.create(request));
        verifyNoInteractions(repository);
    }

    @Test
    void createsClaimNumberFromReportYearAndSequence() {
        when(repository.nextClaimSequence()).thenReturn(42L);
        when(repository.insert(any(), eq("CLM-2026-0000042"), eq(TENANT), eq(POLICY_ID), eq(CLAIMANT_ID),
                eq(ClaimStatus.REGISTERED), eq(LOSS_DATE), eq(REPORT_DATE), eq("GL"),
                eq(new BigDecimal("125000.0000")), eq("USD"), any(Instant.class)))
                .thenReturn(claim("REGISTERED", 0));

        service.create(request(REPORT_DATE, new BigDecimal("125000"), "usd", null));

        verify(repository).insert(any(), eq("CLM-2026-0000042"), eq(TENANT), eq(POLICY_ID), eq(CLAIMANT_ID),
                eq(ClaimStatus.REGISTERED), eq(LOSS_DATE), eq(REPORT_DATE), eq("GL"),
                eq(new BigDecimal("125000.0000")), eq("USD"), any(Instant.class));
    }

    @Test
    void allowsRegisteredToOpenTransition() {
        when(repository.findById(TENANT, CLAIM_ID)).thenReturn(Optional.of(claim("REGISTERED", 3)));
        when(repository.updateStatus(eq(TENANT), eq(CLAIM_ID), eq(ClaimStatus.OPEN), eq(3), any(Instant.class)))
                .thenReturn(claim("OPEN", 4));

        Claim updated = service.changeStatus(TENANT, CLAIM_ID, "open", null);

        assertEquals("OPEN", updated.status());
        assertEquals(4, updated.version());
    }

    @Test
    void rejectsIllegalTransition() {
        when(repository.findById(TENANT, CLAIM_ID)).thenReturn(Optional.of(claim("REGISTERED", 0)));

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.changeStatus(TENANT, CLAIM_ID, "PAID", null));

        assertTrue(error.getMessage().contains("REGISTERED -> PAID"));
        verify(repository, never()).updateStatus(any(), any(), any(), anyInt(), any());
    }

    @Test
    void rejectsUnknownStatus() {
        when(repository.findById(TENANT, CLAIM_ID)).thenReturn(Optional.of(claim("REGISTERED", 0)));

        assertThrows(ClaimValidationException.class,
                () -> service.changeStatus(TENANT, CLAIM_ID, "NOT_A_STATUS", null));
    }

    @Test
    void rejectsStaleVersion() {
        when(repository.findById(TENANT, CLAIM_ID)).thenReturn(Optional.of(claim("REGISTERED", 3)));

        assertThrows(ClaimConflictException.class,
                () -> service.changeStatus(TENANT, CLAIM_ID, "OPEN", 2));
    }

    @Test
    void closedClaimCanOnlyReopen() {
        when(repository.findById(TENANT, CLAIM_ID)).thenReturn(Optional.of(claim("CLOSED", 8)));

        assertThrows(ClaimValidationException.class,
                () -> service.changeStatus(TENANT, CLAIM_ID, "OPEN", null));

        when(repository.updateStatus(eq(TENANT), eq(CLAIM_ID), eq(ClaimStatus.REOPENED), eq(8), any(Instant.class)))
                .thenReturn(claim("REOPENED", 9));
        assertEquals("REOPENED", service.changeStatus(TENANT, CLAIM_ID, "REOPENED", null).status());
    }

    private static CreateClaimRequest request(LocalDate reportDate, BigDecimal reserve, String currency, String status) {
        return new CreateClaimRequest(TENANT, POLICY_ID, CLAIMANT_ID, LOSS_DATE, reportDate,
                "GL", reserve, currency, status);
    }

    private static Claim claim(String status, int version) {
        return new Claim(
                CLAIM_ID,
                TENANT,
                "DIRECT",
                "CLM-2026-0000001",
                POLICY_ID,
                CLAIMANT_ID,
                status,
                LOSS_DATE,
                REPORT_DATE,
                "GL",
                new BigDecimal("125000.0000"),
                "USD",
                version,
                Instant.parse("2026-03-18T09:00:00Z"),
                Instant.parse("2026-03-18T09:00:00Z"));
    }
}
