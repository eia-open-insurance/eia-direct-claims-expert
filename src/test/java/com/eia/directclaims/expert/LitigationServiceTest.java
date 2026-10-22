package com.eia.directclaims.expert;

import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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
import static com.eia.directclaims.expert.Fixtures.litigationWithDamages;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LitigationServiceTest {
    @Mock
    private LitigationRepository repository;
    @Mock
    private ClaimRepository claimRepository;
    @Mock
    private OutboxRepository outbox;

    private LitigationService service;

    @BeforeEach
    void setUp() {
        service = new LitigationService(repository, claimRepository, new ClaimLookup(claimRepository),
                outbox, Clock.fixed(Instant.parse("2026-03-18T09:00:00Z"), ZoneOffset.UTC));
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
                LocalDate.of(2026, 2, 1), null, "ACTIVE", "CV-2026-001", null, null, null);

        assertThrows(ClaimValidationException.class, () -> service.add(TENANT, CLAIM_ID, request));
    }

    @Test
    void rejectsAnUnknownStatus() {
        claimExists();
        LitigationRequest request = new LitigationRequest("DEFENSE", "US-OH", OTHER_PARTY_ID,
                LocalDate.of(2026, 2, 1), null, "LIMBO", null, null, null, null);

        assertThrows(ClaimValidationException.class, () -> service.add(TENANT, CLAIM_ID, request));
    }

    @Test
    void recordsAMatterWithoutDamagesAndMarksTheClaimAsInLitigation() {
        claimExists();
        when(claimRepository.markLitigation(eq(TENANT), eq(CLAIM_ID), eq(0), any(Instant.class)))
                .thenReturn(1);
        when(repository.insert(any(), eq(TENANT), eq(CLAIM_ID), eq("DEFENSE"), eq("US-OH"),
                eq(OTHER_PARTY_ID), eq(LocalDate.of(2026, 2, 1)), eq(LocalDate.of(2026, 9, 1)),
                eq("ACTIVE"), eq("CV-2026-001"), isNull(), isNull(), any(Instant.class)))
                .thenReturn(litigation());

        LitigationMatter created = service.add(TENANT, CLAIM_ID,
                new LitigationRequest("DEFENSE", "US-OH", OTHER_PARTY_ID, LocalDate.of(2026, 2, 1),
                        LocalDate.of(2026, 9, 1), "ACTIVE", "CV-2026-001", null, null, null));

        assertEquals("ACTIVE", created.status());
        assertNull(created.damagesSought());
        assertNull(created.damagesCurrency());
        verify(claimRepository).markLitigation(eq(TENANT), eq(CLAIM_ID), eq(0), any(Instant.class));
        verify(outbox).insert(any(OutboxEvent.class));
    }

    @Test
    void recordsAMatterWithDamagesAndEmitsThemInTheCreatedEvent() {
        claimExists();
        BigDecimal amount = new BigDecimal("275000.00");
        when(claimRepository.markLitigation(eq(TENANT), eq(CLAIM_ID), eq(0), any(Instant.class)))
                .thenReturn(1);
        when(repository.insert(any(), eq(TENANT), eq(CLAIM_ID), eq("DEFENSE"), eq("US-OH"),
                eq(OTHER_PARTY_ID), eq(LocalDate.of(2026, 2, 1)), eq(LocalDate.of(2026, 9, 1)),
                eq("ACTIVE"), eq("CV-2026-001"), eq(new BigDecimal("275000.0000")), eq("USD"),
                any(Instant.class)))
                .thenReturn(litigationWithDamages(new BigDecimal("275000.0000"), "USD"));

        LitigationMatter created = service.add(TENANT, CLAIM_ID,
                new LitigationRequest("DEFENSE", "US-OH", OTHER_PARTY_ID, LocalDate.of(2026, 2, 1),
                        LocalDate.of(2026, 9, 1), "ACTIVE", "CV-2026-001", amount, "usd", null));

        assertEquals(0, created.damagesSought().compareTo(new BigDecimal("275000.0000")));
        assertEquals("USD", created.damagesCurrency());

        EventEnvelope event = capturedEvent();
        assertEquals("direct.claim.litigation-created", event.eventType());
        assertEquals("275000.0000", event.data().get("damagesSought"));
        assertEquals("USD", event.data().get("damagesCurrency"));
    }

    @Test
    void createRejectsADamagesAmountWithoutACurrency() {
        claimExists();

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.add(TENANT, CLAIM_ID,
                        new LitigationRequest("DEFENSE", "US-OH", OTHER_PARTY_ID,
                                LocalDate.of(2026, 2, 1), null, "ACTIVE", null,
                                new BigDecimal("1000.00"), null, null)));

        assertTrue(error.getMessage().contains("damagesSought"));
        assertTrue(error.getMessage().contains("damagesCurrency"));
        verifyNoInteractions(repository);
        verifyNoInteractions(outbox);
    }

    @Test
    void createRejectsADamagesCurrencyWithoutAnAmount() {
        claimExists();

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.add(TENANT, CLAIM_ID,
                        new LitigationRequest("DEFENSE", "US-OH", OTHER_PARTY_ID,
                                LocalDate.of(2026, 2, 1), null, "ACTIVE", null,
                                null, "USD", null)));

        assertTrue(error.getMessage().contains("damagesSought"));
        assertTrue(error.getMessage().contains("damagesCurrency"));
        verifyNoInteractions(repository);
        verifyNoInteractions(outbox);
    }

    @Test
    void createRejectsNonPositiveDamagesSought() {
        claimExists();

        ClaimValidationException zero = assertThrows(ClaimValidationException.class,
                () -> service.add(TENANT, CLAIM_ID,
                        new LitigationRequest("DEFENSE", "US-OH", OTHER_PARTY_ID,
                                LocalDate.of(2026, 2, 1), null, "ACTIVE", null,
                                new BigDecimal("0.00"), "USD", null)));
        ClaimValidationException negative = assertThrows(ClaimValidationException.class,
                () -> service.add(TENANT, CLAIM_ID,
                        new LitigationRequest("DEFENSE", "US-OH", OTHER_PARTY_ID,
                                LocalDate.of(2026, 2, 1), null, "ACTIVE", null,
                                new BigDecimal("-1.00"), "USD", null)));

        assertTrue(zero.getMessage().contains("greater than zero"));
        assertTrue(negative.getMessage().contains("greater than zero"));
        verifyNoInteractions(repository);
        verifyNoInteractions(outbox);
    }

    @Test
    void updateRejectsAStaleVersion() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, LITIGATION_ID)).thenReturn(Optional.of(litigation()));

        LitigationRequest request = new LitigationRequest(null, null, null, null, null,
                "STAYED", null, null, null, 5);

        assertThrows(ClaimConflictException.class,
                () -> service.update(TENANT, CLAIM_ID, LITIGATION_ID, request));
        verify(claimRepository, never()).bumpVersion(any(), any(), anyInt(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void updateAppliesANewStatusAndKeepsTheExistingDamages() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, LITIGATION_ID))
                .thenReturn(Optional.of(litigationWithDamages(new BigDecimal("250000.0000"), "USD")));
        when(claimRepository.bumpVersion(eq(TENANT), eq(CLAIM_ID), eq(0), any(Instant.class)))
                .thenReturn(1);
        when(repository.update(eq(TENANT), eq(CLAIM_ID), eq(LITIGATION_ID), eq("DEFENSE"), eq("US-OH"),
                eq(OTHER_PARTY_ID), eq(LocalDate.of(2026, 2, 1)), eq(LocalDate.of(2026, 9, 1)),
                eq("STAYED"), eq("CV-2026-001"), eq(new BigDecimal("250000.0000")), eq("USD"),
                eq(1), any(Instant.class)))
                .thenReturn(litigationWithDamages(new BigDecimal("250000.0000"), "USD"));

        service.update(TENANT, CLAIM_ID, LITIGATION_ID,
                new LitigationRequest(null, null, null, null, null, "STAYED", null, null, null, 1));

        verify(repository).update(eq(TENANT), eq(CLAIM_ID), eq(LITIGATION_ID), eq("DEFENSE"), eq("US-OH"),
                eq(OTHER_PARTY_ID), eq(LocalDate.of(2026, 2, 1)), eq(LocalDate.of(2026, 9, 1)),
                eq("STAYED"), eq("CV-2026-001"), eq(new BigDecimal("250000.0000")), eq("USD"),
                eq(1), any(Instant.class));
        assertTrue(capturedEvent().data().containsKey("damagesSought"));
    }

    @Test
    void updateAppliesDamagesThatWereAbsent() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, LITIGATION_ID)).thenReturn(Optional.of(litigation()));
        when(claimRepository.bumpVersion(eq(TENANT), eq(CLAIM_ID), eq(0), any(Instant.class)))
                .thenReturn(1);
        when(repository.update(eq(TENANT), eq(CLAIM_ID), eq(LITIGATION_ID), eq("DEFENSE"), eq("US-OH"),
                eq(OTHER_PARTY_ID), eq(LocalDate.of(2026, 2, 1)), eq(LocalDate.of(2026, 9, 1)),
                eq("ACTIVE"), eq("CV-2026-001"), eq(new BigDecimal("300000.0000")), eq("USD"),
                eq(1), any(Instant.class)))
                .thenReturn(litigationWithDamages(new BigDecimal("300000.0000"), "USD"));

        LitigationMatter updated = service.update(TENANT, CLAIM_ID, LITIGATION_ID,
                new LitigationRequest(null, null, null, null, null, null, null,
                        new BigDecimal("300000.00"), "USD", 1));

        assertEquals(0, updated.damagesSought().compareTo(new BigDecimal("300000.0000")));
        assertEquals("USD", updated.damagesCurrency());
        EventEnvelope event = capturedEvent();
        assertEquals("direct.claim.litigation-updated", event.eventType());
        assertEquals("300000.0000", event.data().get("damagesSought"));
        assertEquals("USD", event.data().get("damagesCurrency"));
    }

    @Test
    void updateRejectsAnUnpairedDamagesChange() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, LITIGATION_ID))
                .thenReturn(Optional.of(litigationWithDamages(new BigDecimal("250000.0000"), "USD")));

        assertThrows(ClaimValidationException.class,
                () -> service.update(TENANT, CLAIM_ID, LITIGATION_ID,
                        new LitigationRequest(null, null, null, null, null, null, null,
                                new BigDecimal("350000.00"), null, 1)));

        verify(claimRepository, never()).bumpVersion(any(), any(), anyInt(), any());
        verify(repository, never()).update(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyInt(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void updateRejectsNonPositiveDamagesSought() {
        claimExists();
        when(repository.findById(TENANT, CLAIM_ID, LITIGATION_ID)).thenReturn(Optional.of(litigation()));

        assertThrows(ClaimValidationException.class,
                () -> service.update(TENANT, CLAIM_ID, LITIGATION_ID,
                        new LitigationRequest(null, null, null, null, null, null, null,
                                new BigDecimal("0.00"), "USD", 1)));

        verify(claimRepository, never()).bumpVersion(any(), any(), anyInt(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void rejectsAClaimOutsideTheTenant() {
        when(claimRepository.findById(TENANT, CLAIM_ID)).thenReturn(Optional.empty());

        assertThrows(ClaimNotFoundException.class,
                () -> service.add(TENANT, CLAIM_ID,
                        new LitigationRequest("DEFENSE", "US-OH", OTHER_PARTY_ID,
                                LocalDate.of(2026, 2, 1), null, "ACTIVE", null, null, null, null)));

        verifyNoInteractions(repository);
        verifyNoInteractions(outbox);
    }

    private void claimExists() {
        when(claimRepository.findById(TENANT, CLAIM_ID)).thenReturn(Optional.of(claim("REGISTERED", 0)));
    }

    private EventEnvelope capturedEvent() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(captor.capture());
        return captor.getValue().toEnvelope();
    }

    private static LitigationRequest request(LocalDate filingDate, LocalDate trialDate,
                                             java.util.UUID counsel) {
        return new LitigationRequest("DEFENSE", "US-OH", counsel, filingDate, trialDate, "ACTIVE",
                "CV-2026-001", null, null, null);
    }
}
