package com.eia.directclaims.expert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.eia.directclaims.expert.Fixtures.CLAIM_ID;
import static com.eia.directclaims.expert.Fixtures.LIEN_ID;
import static com.eia.directclaims.expert.Fixtures.TENANT;
import static com.eia.directclaims.expert.Fixtures.claim;
import static com.eia.directclaims.expert.Fixtures.lien;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LienServiceTest {
    @Mock
    private LienRepository repository;
    @Mock
    private ClaimRepository claimRepository;

    private LienService service;

    @BeforeEach
    void setUp() {
        service = new LienService(repository, new ClaimLookup(claimRepository),
                Clock.fixed(Instant.parse("2026-03-18T09:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void resolvingALienRequiresAResolutionType() {
        claimExists();
        LienRequest request = new LienRequest("MEDICARE", "CMS", new BigDecimal("15000.00"), "USD",
                "RESOLVED", null, null);

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.record(TENANT, CLAIM_ID, request));

        assertTrue(error.getMessage().contains("resolutionType"));
    }

    @Test
    void recordsAnOpenLien() {
        claimExists();
        when(repository.insert(any(), eq(TENANT), eq(CLAIM_ID), eq("MEDICARE"), eq("CMS"),
                eq(new BigDecimal("15000.0000")), eq("USD"), eq("IDENTIFIED"), isNull(), isNull(),
                any(Instant.class))).thenReturn(lien("IDENTIFIED"));

        Lien recorded = service.record(TENANT, CLAIM_ID, new LienRequest("MEDICARE", "CMS",
                new BigDecimal("15000.00"), "USD", "IDENTIFIED", null, null));

        assertEquals("IDENTIFIED", recorded.status());
    }

    @Test
    void recordsAResolvedLienWithAResolutionTimestamp() {
        claimExists();
        when(repository.insert(any(), eq(TENANT), eq(CLAIM_ID), eq("MEDICARE"), eq("CMS"),
                eq(new BigDecimal("15000.0000")), eq("USD"), eq("RESOLVED"), eq("NEGOTIATED"),
                any(Instant.class), any(Instant.class))).thenReturn(lien("RESOLVED"));

        service.record(TENANT, CLAIM_ID, new LienRequest("MEDICARE", "CMS",
                new BigDecimal("15000.00"), "USD", "RESOLVED", "NEGOTIATED", null));

        org.mockito.Mockito.verify(repository).insert(any(), eq(TENANT), eq(CLAIM_ID), eq("MEDICARE"),
                eq("CMS"), eq(new BigDecimal("15000.0000")), eq("USD"), eq("RESOLVED"),
                eq("NEGOTIATED"), any(Instant.class), any(Instant.class));
    }

    @Test
    void updateToResolvedRequiresAResolutionType() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, LIEN_ID)).thenReturn(Optional.of(lien("IDENTIFIED")));

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.update(TENANT, CLAIM_ID, LIEN_ID,
                        new LienRequest(null, null, null, null, "RESOLVED", null, 1)));

        assertTrue(error.getMessage().contains("resolutionType"));
    }

    @Test
    void updateRejectsAStaleVersion() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, LIEN_ID)).thenReturn(Optional.of(lien("IDENTIFIED")));

        assertThrows(ClaimConflictException.class, () -> service.update(TENANT, CLAIM_ID, LIEN_ID,
                new LienRequest(null, null, null, null, "VERIFIED", null, 5)));
    }

    @Test
    void updateResolvesALien() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, LIEN_ID)).thenReturn(Optional.of(lien("IDENTIFIED")));
        when(repository.update(eq(TENANT), eq(CLAIM_ID), eq(LIEN_ID), eq("RESOLVED"), eq("NEGOTIATED"),
                any(Instant.class), eq(1), any(Instant.class))).thenReturn(lien("RESOLVED"));

        Lien resolved = service.update(TENANT, CLAIM_ID, LIEN_ID,
                new LienRequest(null, null, null, null, "RESOLVED", "NEGOTIATED", 1));

        assertEquals("RESOLVED", resolved.status());
        org.junit.jupiter.api.Assertions.assertEquals("NEGOTIATED", resolved.resolutionType());
    }

    private void claimExists() {
        when(claimRepository.findById(TENANT, CLAIM_ID)).thenReturn(Optional.of(claim("REGISTERED", 0)));
    }
}
