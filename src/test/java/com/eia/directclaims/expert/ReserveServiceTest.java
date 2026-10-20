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
import static com.eia.directclaims.expert.Fixtures.TENANT;
import static com.eia.directclaims.expert.Fixtures.claim;
import static com.eia.directclaims.expert.Fixtures.reserve;
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
class ReserveServiceTest {
    @Mock
    private ReserveRepository repository;
    @Mock
    private ClaimRepository claimRepository;
    @Mock
    private OutboxRepository outbox;

    private ReserveService service;

    @BeforeEach
    void setUp() {
        service = new ReserveService(repository, new ClaimLookup(claimRepository), outbox,
                Clock.fixed(Instant.parse("2026-03-18T09:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void rejectsANegativeReserve() {
        claimExists();

        assertThrows(ClaimValidationException.class, () -> service.change(TENANT, CLAIM_ID,
                request("INDEMNITY", new BigDecimal("-1.00"), "INITIAL", "Manager", 0)));
        verifyNoInteractions(repository);
    }

    @Test
    void requiresAReason() {
        claimExists();

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.change(TENANT, CLAIM_ID,
                        request("INDEMNITY", new BigDecimal("100.00"), null, "Manager", 0)));

        assertTrue(error.getMessage().contains("reasonCode"));
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsAnUnknownComponent() {
        claimExists();

        assertThrows(ClaimValidationException.class, () -> service.change(TENANT, CLAIM_ID,
                request("NOT_A_COMPONENT", new BigDecimal("100.00"), "INITIAL", null, 0)));
    }

    @Test
    void rejectsAStaleClaimVersion() {
        claimExists();

        ClaimConflictException error = assertThrows(ClaimConflictException.class,
                () -> service.change(TENANT, CLAIM_ID,
                        request("INDEMNITY", new BigDecimal("100.00"), "INITIAL", "Manager", 7)));

        assertTrue(error.getMessage().contains("does not match"));
        verify(repository, never()).postChange(any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), any());
    }

    @Test
    void requiresAuthorityAboveTheClaimLimit() {
        claimExists();

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.change(TENANT, CLAIM_ID,
                        request("INDEMNITY", new BigDecimal("600000.00"), "INITIAL", null, 0)));

        assertTrue(error.getMessage().contains("authority is required"));
    }

    @Test
    void establishesAReserveWithAuthority() {
        claimExists();
        when(repository.postChange(any(), eq("INDEMNITY"), eq(new BigDecimal("600000.0000")), eq("USD"),
                eq(LocalDate.of(2026, 3, 18)), eq("INITIAL"), eq("Initial case reserve"),
                eq("Chief Claims Officer"), eq(0), any(Instant.class))).thenReturn(reserve());

        ReserveHistoryEntry entry = service.change(TENANT, CLAIM_ID,
                request("INDEMNITY", new BigDecimal("600000.00"), "INITIAL", "Chief Claims Officer", 0));

        assertEquals("INDEMNITY", entry.component());
        verify(repository).postChange(any(), eq("INDEMNITY"), eq(new BigDecimal("600000.0000")), eq("USD"),
                eq(LocalDate.of(2026, 3, 18)), eq("INITIAL"), eq("Initial case reserve"),
                eq("Chief Claims Officer"), eq(0), any(Instant.class));
    }

    @Test
    void derivesTheCurrentTotalFromCurrentHistoryRows() {
        when(claimRepository.findById(TENANT, CLAIM_ID)).thenReturn(Optional.of(claim("RESERVED", 3)));
        when(repository.findHistory(TENANT, CLAIM_ID)).thenReturn(List.of(reserve()));
        when(repository.findCurrent(TENANT, CLAIM_ID)).thenReturn(List.of(
                reserve(),
                new ReserveHistoryEntry(java.util.UUID.randomUUID(), TENANT, CLAIM_ID, "DEFENSE",
                        new BigDecimal("25000.0000"), "USD", LocalDate.of(2026, 3, 18), "INITIAL",
                        "Defense reserve", "Manager", 1, true, 3, Fixtures.NOW)));

        ReserveSummary summary = service.summary(TENANT, CLAIM_ID);

        assertEquals(new BigDecimal("125000.0000"), summary.totalCurrentAmount());
        assertEquals(2, summary.components().size());
        assertEquals(1, summary.history().size());
    }

    private void claimExists() {
        when(claimRepository.findById(TENANT, CLAIM_ID))
                .thenReturn(Optional.of(claim("REGISTERED", 0)));
    }

    private static ReserveChangeRequest request(String component,
                                                BigDecimal amount,
                                                String reason,
                                                String authority,
                                                Integer expectedVersion) {
        return new ReserveChangeRequest(component, amount, "USD", LocalDate.of(2026, 3, 18), reason,
                "Initial case reserve", authority, expectedVersion);
    }
}
