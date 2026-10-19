package com.eia.directclaims.expert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.eia.directclaims.expert.Fixtures.CLAIM_ID;
import static com.eia.directclaims.expert.Fixtures.LITIGATION_ID;
import static com.eia.directclaims.expert.Fixtures.OTHER_PARTY_ID;
import static com.eia.directclaims.expert.Fixtures.TENANT;
import static com.eia.directclaims.expert.Fixtures.claim;
import static com.eia.directclaims.expert.Fixtures.litigation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LitigationServiceTest {
    @Mock
    private LitigationRepository repository;
    @Mock
    private ClaimRepository claimRepository;

    private LitigationService service;

    @BeforeEach
    void setUp() {
        service = new LitigationService(repository, claimRepository, new ClaimLookup(claimRepository),
                Clock.fixed(Instant.parse("2026-03-18T09:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void rejectsATrialDateBeforeTheFilingDate() {
        claimExists();

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.add(TENANT, CLAIM_ID,
                        request(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1), OTHER_PARTY_ID)));

        assertTrue(error.getMessage().contains("trialDate"));
        verifyNoInteractions(repository);
    }

    @Test
    void requiresCounsel() {
        claimExists();

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.add(TENANT, CLAIM_ID,
                        request(LocalDate.of(2026, 2, 1), null, null)));

        assertTrue(error.getMessage().contains("counselPartyId"));
        verifyNoInteractions(repository);
    }

    @Test
    void requiresJurisdiction() {
        claimExists();
        LitigationRequest request = new LitigationRequest("DEFENSE", " ", OTHER_PARTY_ID,
                LocalDate.of(2026, 2, 1), null, "ACTIVE", "CV-2026-001", null);

        assertThrows(ClaimValidationException.class, () -> service.add(TENANT, CLAIM_ID, request));
    }

    @Test
    void rejectsAnUnknownStatus() {
        claimExists();
        LitigationRequest request = new LitigationRequest("DEFENSE", "US-OH", OTHER_PARTY_ID,
                LocalDate.of(2026, 2, 1), null, "LIMBO", null, null);

        assertThrows(ClaimValidationException.class, () -> service.add(TENANT, CLAIM_ID, request));
    }

    @Test
    void recordsAMatterAndMarksTheClaimAsInLitigation() {
        claimExists();
        when(repository.insert(any(), eq(TENANT), eq(CLAIM_ID), eq("DEFENSE"), eq("US-OH"),
                eq(OTHER_PARTY_ID), eq(LocalDate.of(2026, 2, 1)), eq(LocalDate.of(2026, 9, 1)),
                eq("ACTIVE"), eq("CV-2026-001"), any(Instant.class))).thenReturn(litigation());

        LitigationMatter created = service.add(TENANT, CLAIM_ID,
                new LitigationRequest("DEFENSE", "US-OH", OTHER_PARTY_ID, LocalDate.of(2026, 2, 1),
                        LocalDate.of(2026, 9, 1), "ACTIVE", "CV-2026-001", null));

        assertEquals("ACTIVE", created.status());
        verify(claimRepository).markLitigation(eq(TENANT), eq(CLAIM_ID), any(Instant.class));
    }

    @Test
    void updateRejectsAStaleVersion() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, LITIGATION_ID)).thenReturn(Optional.of(litigation()));

        LitigationRequest request = new LitigationRequest(null, null, null, null, null, "STAYED", null, 5);

        assertThrows(ClaimConflictException.class,
                () -> service.update(TENANT, CLAIM_ID, LITIGATION_ID, request));
    }

    @Test
    void updateAppliesANewStatus() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, LITIGATION_ID)).thenReturn(Optional.of(litigation()));
        when(repository.update(eq(TENANT), eq(CLAIM_ID), eq(LITIGATION_ID), eq("DEFENSE"), eq("US-OH"),
                eq(OTHER_PARTY_ID), eq(LocalDate.of(2026, 2, 1)), eq(LocalDate.of(2026, 9, 1)),
                eq("STAYED"), eq("CV-2026-001"), eq(1), any(Instant.class))).thenReturn(litigation());

        service.update(TENANT, CLAIM_ID, LITIGATION_ID,
                new LitigationRequest(null, null, null, null, null, "STAYED", null, 1));

        verify(repository).update(eq(TENANT), eq(CLAIM_ID), eq(LITIGATION_ID), eq("DEFENSE"), eq("US-OH"),
                eq(OTHER_PARTY_ID), eq(LocalDate.of(2026, 2, 1)), eq(LocalDate.of(2026, 9, 1)),
                eq("STAYED"), eq("CV-2026-001"), eq(1), any(Instant.class));
    }

    private void claimExists() {
        when(claimRepository.findById(TENANT, CLAIM_ID)).thenReturn(Optional.of(claim("REGISTERED", 0)));
    }

    private static LitigationRequest request(LocalDate filingDate, LocalDate trialDate,
                                             java.util.UUID counsel) {
        return new LitigationRequest("DEFENSE", "US-OH", counsel, filingDate, trialDate, "ACTIVE",
                "CV-2026-001", null);
    }
}
