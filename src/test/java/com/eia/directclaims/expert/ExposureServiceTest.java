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

import static com.eia.directclaims.expert.Fixtures.CLAIMANT_ID;
import static com.eia.directclaims.expert.Fixtures.CLAIM_ID;
import static com.eia.directclaims.expert.Fixtures.EXPOSURE_ID;
import static com.eia.directclaims.expert.Fixtures.OTHER_PARTY_ID;
import static com.eia.directclaims.expert.Fixtures.TENANT;
import static com.eia.directclaims.expert.Fixtures.claim;
import static com.eia.directclaims.expert.Fixtures.exposure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExposureServiceTest {
    @Mock
    private ExposureRepository repository;
    @Mock
    private ClaimRepository claimRepository;

    private ExposureService service;

    @BeforeEach
    void setUp() {
        service = new ExposureService(repository, new ClaimLookup(claimRepository),
                Clock.fixed(Instant.parse("2026-03-18T09:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void rejectsAClaimantThatDoesNotBelongToTheClaim() {
        claimExists();

        assertThrows(ClaimValidationException.class,
                () -> service.add(TENANT, CLAIM_ID, request(OTHER_PARTY_ID, LocalDate.of(2015, 1, 1), null)));
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsAnExposureThatStartsAfterTheReportDate() {
        claimExists();

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.add(TENANT, CLAIM_ID, request(CLAIMANT_ID, LocalDate.of(2026, 3, 19), null)));

        assertTrue(error.getMessage().contains("after reportDate"));
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsAnExposureThatEndsAfterTheReportDate() {
        claimExists();

        assertThrows(ClaimValidationException.class, () -> service.add(TENANT, CLAIM_ID,
                request(CLAIMANT_ID, LocalDate.of(2015, 1, 1), LocalDate.of(2026, 3, 19))));
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsAnEndDateBeforeTheStartDate() {
        claimExists();

        assertThrows(ClaimValidationException.class, () -> service.add(TENANT, CLAIM_ID,
                request(CLAIMANT_ID, LocalDate.of(2016, 1, 1), LocalDate.of(2015, 1, 1))));
        verifyNoInteractions(repository);
    }

    @Test
    void requiresAJurisdiction() {
        claimExists();

        ExposureRequest request = new ExposureRequest(CLAIMANT_ID, "SUBSTANCE", null, "Asbestos",
                null, null, null, " ", LocalDate.of(2015, 1, 1), null, "HIGH", null);

        assertThrows(ClaimValidationException.class, () -> service.add(TENANT, CLAIM_ID, request));
    }

    @Test
    void recordsAnExposureWithinTheReportDate() {
        claimExists();
        when(repository.insert(any(), eq(TENANT), eq(CLAIM_ID), eq(CLAIMANT_ID), eq("SUBSTANCE"),
                isNull(), eq("Chrysotile asbestos"), eq("Akron plant"), isNull(), eq("Machinist"),
                eq("US-OH"), eq(LocalDate.of(2015, 1, 1)), eq(LocalDate.of(2020, 12, 31)), eq("HIGH"),
                any(Instant.class))).thenReturn(exposure());

        ClaimExposure created = service.add(TENANT, CLAIM_ID,
                request(CLAIMANT_ID, LocalDate.of(2015, 1, 1), LocalDate.of(2020, 12, 31)));

        assertEquals("SUBSTANCE", created.exposureType());
    }

    @Test
    void rejectsAnUnknownExposureType() {
        claimExists();
        ExposureRequest request = new ExposureRequest(CLAIMANT_ID, "MAGIC", null, null, null, null, null,
                "US-OH", LocalDate.of(2015, 1, 1), null, null, null);

        assertThrows(ClaimValidationException.class, () -> service.add(TENANT, CLAIM_ID, request));
    }

    @Test
    void updateRejectsAStaleVersion() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, EXPOSURE_ID)).thenReturn(Optional.of(exposure()));

        ExposureRequest request = new ExposureRequest(CLAIMANT_ID, null, null, null, null, null, null,
                null, null, null, null, 7);

        assertThrows(ClaimConflictException.class,
                () -> service.update(TENANT, CLAIM_ID, EXPOSURE_ID, request));
    }

    @Test
    void updateRevalidatesDatesAgainstTheReportDate() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, EXPOSURE_ID)).thenReturn(Optional.of(exposure()));

        ExposureRequest request = new ExposureRequest(null, null, null, null, null, null, null, null,
                LocalDate.of(2026, 3, 19), null, null, 1);

        assertThrows(ClaimValidationException.class,
                () -> service.update(TENANT, CLAIM_ID, EXPOSURE_ID, request));
    }

    private void claimExists() {
        when(claimRepository.findById(TENANT, CLAIM_ID)).thenReturn(Optional.of(claim("REGISTERED", 0)));
    }

    private static ExposureRequest request(java.util.UUID claimant,
                                           LocalDate from,
                                           LocalDate to) {
        return new ExposureRequest(claimant, "SUBSTANCE", null, "Chrysotile asbestos", "Akron plant",
                null, "Machinist", "US-OH", from, to, "HIGH", null);
    }
}

