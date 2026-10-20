package com.eia.directclaims.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Append-only claim notes.
 *
 * <p>There is deliberately no update or delete command. Adding a note bumps the
 * parent claim version and writes {@code direct.claim.note-added} in the same
 * transaction, so a rollback leaves neither the note nor the event behind.</p>
 */
@Service
public class ClaimNoteService {
    private final ClaimNoteRepository repository;
    private final ClaimRepository claimRepository;
    private final ClaimLookup claims;
    private final OutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public ClaimNoteService(ClaimNoteRepository repository,
                            ClaimRepository claimRepository,
                            ClaimLookup claims,
                            OutboxRepository outbox) {
        this(repository, claimRepository, claims, outbox, Clock.systemUTC());
    }

    ClaimNoteService(ClaimNoteRepository repository,
                     ClaimRepository claimRepository,
                     ClaimLookup claims,
                     OutboxRepository outbox,
                     Clock clock) {
        this.repository = repository;
        this.claimRepository = claimRepository;
        this.claims = claims;
        this.outbox = outbox;
        this.clock = clock;
    }

    public List<ClaimNote> list(UUID tenantId, UUID claimId) {
        claims.require(tenantId, claimId);
        return repository.findAll(tenantId, claimId);
    }

    @Transactional
    public ClaimNote add(UUID tenantId, UUID claimId, CreateNoteRequest request) {
        Claim claim = claims.require(tenantId, claimId);
        String noteType = Vocab.normalize(Vocab.NOTE_TYPES, request.noteType(), "noteType");
        String body = Vocab.requiredText(request.body(), "body", 8000);
        String author = Vocab.requiredText(request.author(), "author", 120);

        Instant now = Instant.now(clock);
        String noteNumber = String.format("NTE-%d-%07d", LocalDate.now(clock).getYear(),
                repository.nextNoteSequence());
        ClaimNote note = repository.insert(UuidV7.next(), tenantId, claimId, noteNumber, noteType,
                body, author, now);
        int claimVersion = claimRepository.bumpVersion(tenantId, claimId, claim.version(), now);
        outbox.insert(OutboxEvent.fromEnvelope(ClaimEvents.noteAdded(claim, note, claimVersion, now)));
        return note;
    }
}
