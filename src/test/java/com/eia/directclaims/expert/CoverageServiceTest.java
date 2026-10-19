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
import static com.eia.directclaims.expert.Fixtures.OTHER_PARTY_ID;
import static com.eia.directclaims.expert.Fixtures.TENANT;
import static com.eia.directclaims.expert.Fixtures.claim;
import static com.eia.directclaims.expert.Fixtures.coverage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoverageServiceTest {
    @Mock
    private CoverageRepository repository;
    @Mock
    private InjuryRepository injuryRepository;
    @Mock
    private ExposureRepository exposureRepository;
    @Mock
    private ClaimRepository claimRepository;

    private CoverageService service;

    @BeforeEach
    void setUp() {
        service = new CoverageService(repository, injuryRepository, exposureRepository,
                new ClaimLookup(claimRepository),
                Clock.fixed(Instant.parse("2026-03-18T09:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void refusesToAcceptCoverageBeforeAnyInjuryOrExposure() {
        massTortExists();
        when(injuryRepository.count(TENANT, CLAIM_ID)).thenReturn(0L);
        when(exposureRepository.count(TENANT, CLAIM_ID)).thenReturn(0L);

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.decide(TENANT, CLAIM_ID, request("COVERED")));

        assertTrue(error.getMessage().contains("injury or exposure"));
        verifyNoInteractions(repository);
    }

    @Test
    void massTortRequiresAnExposureEvenWhenAnInjuryExists() {
        massTortExists();
        when(injuryRepository.count(TENANT, CLAIM_ID)).thenReturn(1L);
        when(exposureRepository.count(TENANT, CLAIM_ID)).thenReturn(0L);

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.decide(TENANT, CLAIM_ID, request("COVERED")));

        assertTrue(error.getMessage().contains("exposure"));
    }

    @Test
    void denialDoesNotRequireInjuryOrExposureFacts() {
        massTortExists();
        when(repository.decide(any(), eq("DENIED"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(coverage("DENIED"));

        CoverageDecision decision = service.decide(TENANT, CLAIM_ID, request("DENIED"));

        assertEquals("DENIED", decision.position());
        verifyNoInteractions(injuryRepository, exposureRepository);
    }

    @Test
    void recordsACoveredDecisionWhenFactsExist() {
        massTortExists();
        when(injuryRepository.count(TENANT, CLAIM_ID)).thenReturn(1L);
        when(exposureRepository.count(TENANT, CLAIM_ID)).thenReturn(1L);
        when(repository.decide(any(), eq("COVERED"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(coverage("COVERED"));

        CoverageDecision decision = service.decide(TENANT, CLAIM_ID, request("COVERED"));

        assertEquals("COVERED", decision.position());
        assertEquals("FINAL", decision.status());
    }

    @Test
    void requiresABasis() {
        massTortExists();

        assertThrows(ClaimValidationException.class, () -> service.decide(TENANT, CLAIM_ID,
                new CoverageDecisionRequest("DENIED", " ", null, null, null,
                        LocalDate.of(2026, 3, 18), OTHER_PARTY_ID)));
    }

    @Test
    void requiresAReviewer() {
        massTortExists();

        assertThrows(ClaimValidationException.class, () -> service.decide(TENANT, CLAIM_ID,
                new CoverageDecisionRequest("DENIED", "No coverage for this claim", null, null, null,
                        LocalDate.of(2026, 3, 18), null)));
    }

    @Test
    void rejectsAnAllocationPercentageAboveOne() {
        massTortExists();

        assertThrows(ClaimValidationException.class, () -> service.decide(TENANT, CLAIM_ID,
                new CoverageDecisionRequest("DENIED", "No coverage", null, "PRO_RATA_TIME",
                        new BigDecimal("1.5"), LocalDate.of(2026, 3, 18), OTHER_PARTY_ID)));
    }

    @Test
    void getReportsAMissingDecision() {
        when(claimRepository.findById(TENANT, CLAIM_ID))
                .thenReturn(Optional.of(claim("REGISTERED", 0)));
        when(repository.findLatestFinal(TENANT, CLAIM_ID)).thenReturn(Optional.empty());

        assertThrows(CoverageDecisionNotFoundException.class, () -> service.get(TENANT, CLAIM_ID));
    }

    private void massTortExists() {
        when(claimRepository.findById(TENANT, CLAIM_ID)).thenReturn(Optional.of(
                claim("MASS_TORT", "REGISTERED", 0, new BigDecimal("500000.0000"))));
    }

    private static CoverageDecisionRequest request(String position) {
        return new CoverageDecisionRequest(position, "Policy year 2024 responds", "Coverage A",
                "PRO_RATA_TIME", new BigDecimal("0.25"), LocalDate.of(2026, 3, 18), OTHER_PARTY_ID);
    }
}
