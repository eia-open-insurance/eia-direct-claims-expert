package com.eia.directclaims.expert;

import com.eia.platform.libs.outbox.OutboxRepository;
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
import java.util.List;
import java.util.Optional;

import static com.eia.directclaims.expert.Fixtures.CLAIM_ID;
import static com.eia.directclaims.expert.Fixtures.LOSS_DATE;
import static com.eia.directclaims.expert.Fixtures.REPORT_DATE;
import static com.eia.directclaims.expert.Fixtures.TENANT;
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
    @Mock
    private ClaimRepository repository;
    @Mock
    private CoverageRepository coverageRepository;
    @Mock
    private ReserveRepository reserveRepository;
    @Mock
    private LienRepository lienRepository;
    @Mock
    private OutboxRepository outbox;

    private ClaimService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-03-18T09:00:00Z"), ZoneOffset.UTC);
        service = new ClaimService(repository, coverageRepository, reserveRepository, lienRepository,
                outbox, clock);
    }

    @Test
    void rejectsReportDateBeforeLossDate() {
        CreateClaimRequest request = request(LOSS_DATE.minusDays(1), new BigDecimal("100.00"), null, null);

        ClaimValidationException error = assertThrows(ClaimValidationException.class, () -> service.create(request));

        assertEquals("reportDate must be on or after lossDate", error.getMessage());
        verifyNoInteractions(repository);
    }

    @Test
    void acceptsReportDateEqualToLossDateAndSeedsTheInitialReserve() {
        stubCreate();
        service.create(request(LOSS_DATE, new BigDecimal("0.00"), null, null));

        verify(reserveRepository).seedInitial(eq(Fixtures.TENANT), eq(Fixtures.CLAIM_ID),
                eq(new BigDecimal("0.0000")), eq("USD"), any(Instant.class));
    }

    @Test
    void rejectsNegativeReserve() {
        assertThrows(ClaimValidationException.class,
                () -> service.create(request(REPORT_DATE, new BigDecimal("-0.01"), null, null)));
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsNonInitialStatusOnCreate() {
        assertThrows(ClaimValidationException.class,
                () -> service.create(request(REPORT_DATE, new BigDecimal("100.00"), null, "PAID")));
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsUnknownClaimType() {
        assertThrows(ClaimValidationException.class,
                () -> service.create(request(REPORT_DATE, new BigDecimal("100.00"), "NOT_A_TORT", null)));
        verifyNoInteractions(repository);
    }

    @Test
    void createsClaimNumberFromReportYearAndSequenceAndDefaultClaimType() {
        stubCreate();
        service.create(request(REPORT_DATE, new BigDecimal("125000"), null, null));

        verify(repository).insert(any(), eq("CLM-2026-0000001"), eq(Fixtures.TENANT), eq(Fixtures.POLICY_ID),
                eq(Fixtures.CLAIMANT_ID), eq("BODILY_INJURY"), eq(ClaimStatus.REGISTERED), eq(LOSS_DATE),
                eq(REPORT_DATE), eq("GL"), eq(new BigDecimal("125000.0000")), eq("USD"),
                eq(new BigDecimal("500000.0000")), eq("USD"), any(Instant.class));
        verify(reserveRepository).seedInitial(eq(Fixtures.TENANT), eq(Fixtures.CLAIM_ID),
                eq(new BigDecimal("125000.0000")), eq("USD"), any(Instant.class));
    }

    @Test
    void listParsesStatusAndForwardsTheClaimantPartyFilter() {
        when(repository.findAll(Fixtures.TENANT, ClaimStatus.OPEN, Fixtures.CLAIMANT_ID))
                .thenReturn(List.of(Fixtures.claim("OPEN", 1)));

        List<Claim> claims = service.list(Fixtures.TENANT, "open", Fixtures.CLAIMANT_ID);

        assertEquals(1, claims.size());
        verify(repository).findAll(Fixtures.TENANT, ClaimStatus.OPEN, Fixtures.CLAIMANT_ID);
    }

    @Test
    void listTreatsBlankStatusAsNoFilterAndForwardsNoClaimant() {
        when(repository.findAll(Fixtures.TENANT, null, null))
                .thenReturn(List.of(Fixtures.claim("REGISTERED", 0)));

        List<Claim> claims = service.list(Fixtures.TENANT, " ", null);

        assertEquals(1, claims.size());
        verify(repository).findAll(Fixtures.TENANT, null, null);
    }

    @Test
    void refusesToOpenWithoutAFinalCoverageDecision() {
        when(repository.findById(Fixtures.TENANT, Fixtures.CLAIM_ID))
                .thenReturn(Optional.of(Fixtures.claim("REGISTERED", 0)));
        when(coverageRepository.findLatestFinal(Fixtures.TENANT, Fixtures.CLAIM_ID)).thenReturn(Optional.empty());

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.changeStatus(Fixtures.TENANT, Fixtures.CLAIM_ID, "OPEN", null));

        assertEquals("A final coverage decision is required before OPEN", error.getMessage());
        verify(repository, never()).updateStatus(any(), any(), any(), anyInt(), any());
    }

    @Test
    void refusesToOpenADeniedClaim() {
        when(repository.findById(Fixtures.TENANT, Fixtures.CLAIM_ID))
                .thenReturn(Optional.of(Fixtures.claim("REGISTERED", 0)));
        when(coverageRepository.findLatestFinal(Fixtures.TENANT, Fixtures.CLAIM_ID))
                .thenReturn(Optional.of(Fixtures.coverage("DENIED")));

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.changeStatus(Fixtures.TENANT, Fixtures.CLAIM_ID, "OPEN", null));

        assertTrue(error.getMessage().contains("DENIED"));
    }

    @Test
    void opensACoveredClaim() {
        when(repository.findById(Fixtures.TENANT, Fixtures.CLAIM_ID))
                .thenReturn(Optional.of(Fixtures.claim("REGISTERED", 0)));
        when(coverageRepository.findLatestFinal(Fixtures.TENANT, Fixtures.CLAIM_ID))
                .thenReturn(Optional.of(Fixtures.coverage("COVERED")));
        when(repository.updateStatus(eq(Fixtures.TENANT), eq(Fixtures.CLAIM_ID), eq(ClaimStatus.OPEN),
                eq(0), any(Instant.class))).thenReturn(Fixtures.claim("OPEN", 1));

        Claim updated = service.changeStatus(Fixtures.TENANT, Fixtures.CLAIM_ID, "open", null);

        assertEquals("OPEN", updated.status());
    }

    @Test
    void closeIsBlockedByAnUnresolvedLien() {
        when(repository.findById(Fixtures.TENANT, Fixtures.CLAIM_ID))
                .thenReturn(Optional.of(Fixtures.claim("PAID", 4)));
        when(lienRepository.countUnresolved(Fixtures.TENANT, Fixtures.CLAIM_ID)).thenReturn(1L);

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.close(Fixtures.TENANT, Fixtures.CLAIM_ID, null));

        assertTrue(error.getMessage().contains("lien"));
        verify(repository, never()).updateStatus(any(), any(), any(), anyInt(), any());
    }

    @Test
    void closeIsBlockedByAnOpenReserve() {
        when(repository.findById(Fixtures.TENANT, Fixtures.CLAIM_ID))
                .thenReturn(Optional.of(Fixtures.claim("PAID", 4)));
        when(lienRepository.countUnresolved(Fixtures.TENANT, Fixtures.CLAIM_ID)).thenReturn(0L);
        when(reserveRepository.currentTotal(Fixtures.TENANT, Fixtures.CLAIM_ID))
                .thenReturn(new BigDecimal("100.0000"));

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.close(Fixtures.TENANT, Fixtures.CLAIM_ID, null));

        assertTrue(error.getMessage().contains("open reserve"));
    }

    @Test
    void closeRequiresALegalLifecycleTransition() {
        when(repository.findById(Fixtures.TENANT, Fixtures.CLAIM_ID))
                .thenReturn(Optional.of(Fixtures.claim("REGISTERED", 0)));
        when(lienRepository.countUnresolved(Fixtures.TENANT, Fixtures.CLAIM_ID)).thenReturn(0L);
        when(reserveRepository.currentTotal(Fixtures.TENANT, Fixtures.CLAIM_ID))
                .thenReturn(new BigDecimal("0.0000"));

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.close(Fixtures.TENANT, Fixtures.CLAIM_ID, null));

        assertTrue(error.getMessage().contains("REGISTERED"));
    }

    @Test
    void closesAPaidClaimWithNoLisAndNoReserve() {
        when(repository.findById(Fixtures.TENANT, Fixtures.CLAIM_ID))
                .thenReturn(Optional.of(Fixtures.claim("PAID", 4)));
        when(lienRepository.countUnresolved(Fixtures.TENANT, Fixtures.CLAIM_ID)).thenReturn(0L);
        when(reserveRepository.currentTotal(Fixtures.TENANT, Fixtures.CLAIM_ID))
                .thenReturn(new BigDecimal("0.0000"));
        when(repository.updateStatus(eq(Fixtures.TENANT), eq(Fixtures.CLAIM_ID), eq(ClaimStatus.CLOSED),
                eq(4), any(Instant.class))).thenReturn(Fixtures.claim("CLOSED", 5));

        Claim closed = service.close(Fixtures.TENANT, Fixtures.CLAIM_ID, 4);

        assertEquals("CLOSED", closed.status());
    }

    @Test
    void closeRejectsAStaleVersion() {
        when(repository.findById(Fixtures.TENANT, Fixtures.CLAIM_ID))
                .thenReturn(Optional.of(Fixtures.claim("PAID", 4)));
        when(lienRepository.countUnresolved(Fixtures.TENANT, Fixtures.CLAIM_ID)).thenReturn(0L);
        when(reserveRepository.currentTotal(Fixtures.TENANT, Fixtures.CLAIM_ID))
                .thenReturn(new BigDecimal("0.0000"));

        assertThrows(ClaimConflictException.class,
                () -> service.close(Fixtures.TENANT, Fixtures.CLAIM_ID, 3));
    }

    private void stubCreate() {
        when(repository.nextClaimSequence()).thenReturn(1L);
        when(repository.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any())).thenReturn(Fixtures.claim("REGISTERED", 0));
        when(repository.findById(Fixtures.TENANT, Fixtures.CLAIM_ID))
                .thenReturn(Optional.of(Fixtures.claim("REGISTERED", 0)));
    }

    private static CreateClaimRequest request(LocalDate reportDate,
                                              BigDecimal reserve,
                                              String claimType,
                                              String status) {
        return new CreateClaimRequest(Fixtures.TENANT, Fixtures.POLICY_ID, Fixtures.CLAIMANT_ID,
                LOSS_DATE, reportDate, "GL", claimType, reserve, "USD",
                new BigDecimal("500000.00"), "USD", status);
    }
}

