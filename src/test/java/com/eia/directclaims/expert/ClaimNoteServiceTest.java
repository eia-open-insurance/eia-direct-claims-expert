package com.eia.directclaims.expert;

import com.eia.platform.libs.events.EventJson;
import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.eia.directclaims.expert.Fixtures.CLAIM_ID;
import static com.eia.directclaims.expert.Fixtures.NOTE_ID;
import static com.eia.directclaims.expert.Fixtures.NOW;
import static com.eia.directclaims.expert.Fixtures.TENANT;
import static com.eia.directclaims.expert.Fixtures.claim;
import static com.eia.directclaims.expert.Fixtures.note;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimNoteServiceTest {
    @Mock
    private ClaimNoteRepository repository;
    @Mock
    private ClaimRepository claimRepository;
    @Mock
    private OutboxRepository outbox;
    @Mock
    private ClaimCommandIdempotencyRepository idempotency;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private ClaimNoteService service;

    @BeforeEach
    void setUp() {
        service = new ClaimNoteService(repository, claimRepository, new ClaimLookup(claimRepository),
                outbox, idempotency, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void listsNotesForTheClaim() {
        claimExists(0);
        List<ClaimNote> notes = List.of(note("DIARY", "Called the claimant"));
        when(repository.findAll(TENANT, CLAIM_ID)).thenReturn(notes);

        List<ClaimNote> found = service.list(TENANT, CLAIM_ID);

        assertSame(notes, found);
        verifyNoInteractions(outbox);
    }

    @Test
    void addsANoteAndWritesTheNoteAddedEvent() {
        claimExists(0);
        when(repository.nextNoteSequence()).thenReturn(1L);
        when(repository.insert(any(UUID.class), eq(TENANT), eq(CLAIM_ID), eq("NTE-2026-0000001"),
                eq("DIARY"), eq("Called the claimant"), eq("adjuster@example.com"),
                any(Instant.class))).thenReturn(note("DIARY", "Called the claimant"));
        when(claimRepository.bumpVersion(eq(TENANT), eq(CLAIM_ID), eq(0), any(Instant.class)))
                .thenReturn(1);

        ClaimNote added = service.add(TENANT, CLAIM_ID,
                new CreateNoteRequest("DIARY", "Called the claimant", "adjuster@example.com"));

        assertEquals("DIARY", added.noteType());
        assertEquals("NTE-2026-0000001", added.noteNumber());

        OutboxEvent event = capturedEvent();
        assertEquals(ClaimEvents.NOTE_ADDED, event.eventType());
        assertEquals("Claim", event.aggregateType());
        assertEquals(CLAIM_ID, event.aggregateId());
        assertEquals(2L, event.aggregateVersion());

        Map<String, Object> payload = payload(event);
        assertEquals(CLAIM_ID.toString(), payload.get("claimId"));
        assertEquals(NOTE_ID.toString(), payload.get("noteId"));
        assertEquals("NTE-2026-0000001", payload.get("noteNumber"));
        assertEquals("DIARY", payload.get("noteType"));
        assertEquals("Called the claimant", payload.get("body"));
        assertEquals("adjuster@example.com", payload.get("author"));
        assertEquals(1, payload.get("version"));
    }

    @Test
    void rejectsAnUnknownNoteType() {
        claimExists(0);

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.add(TENANT, CLAIM_ID,
                        new CreateNoteRequest("EMAIL", "Called the claimant", "adjuster@example.com")));

        assertTrue(error.getMessage().contains("Unknown noteType"));
        verify(repository, never()).insert(any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void rejectsAnEmptyBody() {
        claimExists(0);

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.add(TENANT, CLAIM_ID,
                        new CreateNoteRequest("DIARY", "   ", "adjuster@example.com")));

        assertTrue(error.getMessage().contains("body is required"));
        verifyNoInteractions(outbox);
    }

    @Test
    void requiresAnAuthor() {
        claimExists(0);

        ClaimValidationException error = assertThrows(ClaimValidationException.class,
                () -> service.add(TENANT, CLAIM_ID,
                        new CreateNoteRequest("DIARY", "Called the claimant", " ")));

        assertTrue(error.getMessage().contains("author is required"));
        verifyNoInteractions(outbox);
    }

    @Test
    void aClaimOutsideTheTenantIsNotFound() {
        when(claimRepository.findById(TENANT, CLAIM_ID)).thenReturn(Optional.empty());

        assertThrows(ClaimNotFoundException.class, () -> service.list(TENANT, CLAIM_ID));

        verifyNoInteractions(outbox);
    }

    private void claimExists(int version) {
        when(claimRepository.findById(TENANT, CLAIM_ID))
                .thenReturn(Optional.of(claim("REGISTERED", version)));
    }

    private OutboxEvent capturedEvent() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(captor.capture());
        return captor.getValue();
    }

    private static Map<String, Object> payload(OutboxEvent event) {
        return EventJson.toMap(event.payload());
    }
}
