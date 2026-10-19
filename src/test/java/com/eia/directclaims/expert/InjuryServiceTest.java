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
import java.util.List;
import java.util.Optional;

import static com.eia.directclaims.expert.Fixtures.CLAIMANT_ID;
import static com.eia.directclaims.expert.Fixtures.CLAIM_ID;
import static com.eia.directclaims.expert.Fixtures.INJURY_ID;
import static com.eia.directclaims.expert.Fixtures.OTHER_PARTY_ID;
import static com.eia.directclaims.expert.Fixtures.TENANT;
import static com.eia.directclaims.expert.Fixtures.claim;
import static com.eia.directclaims.expert.Fixtures.injury;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InjuryServiceTest {
    @Mock
    private InjuryRepository repository;
    @Mock
    private ClaimRepository claimRepository;

    private InjuryService service;

    @BeforeEach
    void setUp() {
        service = new InjuryService(repository, new ClaimLookup(claimRepository),
                Clock.fixed(Instant.parse("2026-03-18T09:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void rejectsAClaimantThatDoesNotBelongToTheClaim() {
        claimExists();
        InjuryRequest request = request(OTHER_PARTY_ID, "MESOTHELIOMA", "SEVERE", null);

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.add(TENANT, CLAIM_ID, request));

        assertTrue(error.getMessage().contains("not a claimant"));
        verifyNoInteractions(repository);
    }

    @Test
    void requiresAClaimant() {
        claimExists();
        InjuryRequest request = request(null, "MESOTHELIOMA", "SEVERE", null);

        assertThrows(ClaimValidationException.class, () -> service.add(TENANT, CLAIM_ID, request));
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsAnUnknownInjuryType() {
        claimExists();

        assertThrows(ClaimValidationException.class,
                () -> service.add(TENANT, CLAIM_ID, request(CLAIMANT_ID, "BROKEN", "SEVERE", null)));
    }

    @Test
    void requiresASeverity() {
        claimExists();

        assertThrows(ClaimValidationException.class,
                () -> service.add(TENANT, CLAIM_ID, request(CLAIMANT_ID, "MESOTHELIOMA", null, null)));
    }

    @Test
    void recordsAnInjuryWithDiagnosisCodes() {
        claimExists();
        when(repository.insert(any(), eq(TENANT), eq(CLAIM_ID), eq(CLAIMANT_ID), eq("MESOTHELIOMA"),
                eq("LUNG"), eq(List.of("C45.0")), eq("Pleural mesothelioma"), eq("SEVERE"),
                eq(LocalDate.of(2026, 1, 10)), any(Instant.class))).thenReturn(injury());

        ClaimInjury created = service.add(TENANT, CLAIM_ID, request(CLAIMANT_ID, "mesothelioma", "severe",
                LocalDate.of(2026, 1, 10)));

        assertEquals("MESOTHELIOMA", created.injuryType());
        assertEquals(1, created.version());
    }

    @Test
    void updateRejectsAStaleVersion() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, INJURY_ID)).thenReturn(Optional.of(injury()));

        InjuryRequest request = new InjuryRequest(CLAIMANT_ID, "MESOTHELIOMA", "LUNG", List.of("C45.0"),
                null, "SEVERE", null, 9);

        assertThrows(ClaimConflictException.class,
                () -> service.update(TENANT, CLAIM_ID, INJURY_ID, request));
        verify(repository, org.mockito.Mockito.never()).update(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void updateMergesOmittedFields() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, INJURY_ID)).thenReturn(Optional.of(injury()));
        when(repository.update(eq(TENANT), eq(CLAIM_ID), eq(INJURY_ID), eq(CLAIMANT_ID),
                eq("MESOTHELIOMA"), eq("LUNG"), eq(List.of("C45.0")), eq("Pleural mesothelioma"),
                eq("SEVERE"), eq(LocalDate.of(2026, 1, 10)), eq(1), any(Instant.class)))
                .thenReturn(injury());

        InjuryRequest request = new InjuryRequest(null, null, null, null, null, null, null, 1);
        service.update(TENANT, CLAIM_ID, INJURY_ID, request);

        verify(repository).update(eq(TENANT), eq(CLAIM_ID), eq(INJURY_ID), eq(CLAIMANT_ID),
                eq("MESOTHELIOMA"), eq("LUNG"), eq(List.of("C45.0")), eq("Pleural mesothelioma"),
                eq("SEVERE"), eq(LocalDate.of(2026, 1, 10)), eq(1), any(Instant.class));
    }

    @Test
    void updateRequiresAnExpectedVersion() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, INJURY_ID)).thenReturn(Optional.of(injury()));

        assertThrows(ClaimValidationException.class,
                () -> service.update(TENANT, CLAIM_ID, INJURY_ID,
                        new InjuryRequest(null, null, null, null, null, null, null, null)));
    }

    private void claimExists() {
        when(claimRepository.findById(TENANT, CLAIM_ID)).thenReturn(Optional.of(claim("REGISTERED", 0)));
    }

    private static InjuryRequest request(java.util.UUID claimant,
                                         String type,
                                         String severity,
                                         LocalDate diagnosisDate) {
        return new InjuryRequest(claimant, type, "LUNG", List.of("C45.0"), "Pleural mesothelioma",
                severity, diagnosisDate, null);
    }
}

