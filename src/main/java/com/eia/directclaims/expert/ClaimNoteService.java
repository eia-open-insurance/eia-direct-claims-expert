package com.eia.directclaims.expert;

import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Append-only claim notes.
 *
 * <p>There is deliberately no update or delete command. Adding a note bumps the
 * parent claim version and writes {@code direct.claim.note-added} in the same
 * transaction, so a rollback leaves neither the note nor the event behind.</p>
 *
 * <p>Add accepts an optional {@code Idempotency-Key}. The key is reserved
 * atomically with the note and its event, so a rollback leaves no reservation
 * and a simultaneous same-key command loses the reservation instead of raising
 * a database constraint error. The loser reads the winner's committed row: a
 * retry with the same canonical body replays the original 201 response with no
 * second note or event, while a different body is 409
 * {@code EIA-DIR-4091}.</p>
 */
@Service
public class ClaimNoteService {
    private final ClaimNoteRepository repository;
    private final ClaimRepository claimRepository;
    private final ClaimLookup claims;
    private final OutboxRepository outbox;
    private final ClaimCommandIdempotencyRepository idempotency;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public ClaimNoteService(ClaimNoteRepository repository,
                            ClaimRepository claimRepository,
                            ClaimLookup claims,
                            OutboxRepository outbox,
                            ClaimCommandIdempotencyRepository idempotency,
                            ObjectMapper objectMapper) {
        this(repository, claimRepository, claims, outbox, idempotency, objectMapper,
                Clock.systemUTC());
    }

    ClaimNoteService(ClaimNoteRepository repository,
                     ClaimRepository claimRepository,
                     ClaimLookup claims,
                     OutboxRepository outbox,
                     ClaimCommandIdempotencyRepository idempotency,
                     ObjectMapper objectMapper,
                     Clock clock) {
        this.repository = repository;
        this.claimRepository = claimRepository;
        this.claims = claims;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public List<ClaimNote> list(UUID tenantId, UUID claimId) {
        claims.require(tenantId, claimId);
        return repository.findAll(tenantId, claimId);
    }

    /** Compatibility overload for callers that do not use idempotency keys. */
    @Transactional
    public ClaimNote add(UUID tenantId, UUID claimId, CreateNoteRequest request) {
        return add(tenantId, claimId, request, null).value();
    }

    @Transactional
    public ClaimCommandResult<ClaimNote> add(UUID tenantId,
                                             UUID claimId,
                                             CreateNoteRequest request,
                                             String rawIdempotencyKey) {
        String idempotencyKey = IdempotencyKeys.normalize(rawIdempotencyKey);
        String requestHash = IdempotencyKeys.noteHash(claimId, request);
        Instant now = Instant.now(clock);
        if (idempotencyKey != null) {
            Optional<ClaimCommandIdempotency> existing = idempotency.find(
                    tenantId, idempotencyKey, ClaimCommandIdempotencyRepository.NOTE_CREATE);
            if (existing.isPresent()) {
                return replay(existing.get(), requestHash);
            }
            if (!idempotency.reserve(tenantId, idempotencyKey,
                    ClaimCommandIdempotencyRepository.NOTE_CREATE, requestHash, now)) {
                return replay(winner(tenantId, idempotencyKey,
                        ClaimCommandIdempotencyRepository.NOTE_CREATE), requestHash);
            }
        }

        Claim claim = claims.require(tenantId, claimId);
        String noteType = Vocab.normalize(Vocab.NOTE_TYPES, request.noteType(), "noteType");
        String body = Vocab.requiredText(request.body(), "body", 8000);
        String author = Vocab.requiredText(request.author(), "author", 120);

        String noteNumber = String.format("NTE-%d-%07d", LocalDate.now(clock).getYear(),
                repository.nextNoteSequence());
        ClaimNote note = repository.insert(UuidV7.next(), tenantId, claimId, noteNumber, noteType,
                body, author, now);
        int claimVersion = claimRepository.bumpVersion(tenantId, claimId, claim.version(), now);
        outbox.insert(OutboxEvent.fromEnvelope(ClaimEvents.noteAdded(claim, note, claimVersion, now)));

        String responseBody = json(note);
        if (idempotencyKey != null) {
            idempotency.complete(tenantId, idempotencyKey,
                    ClaimCommandIdempotencyRepository.NOTE_CREATE, 201, responseBody);
        }
        return new ClaimCommandResult<>(note, responseBody, false, idempotencyKey != null);
    }

    private ClaimCommandResult<ClaimNote> replay(ClaimCommandIdempotency record, String requestHash) {
        if (!record.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException(
                    "Idempotency-Key was already used with a different request.");
        }
        if (record.responseBody() == null) {
            throw new IllegalStateException(
                    "Idempotency reservation has no stored response to replay");
        }
        return new ClaimCommandResult<>(null, record.responseBody(), true, true);
    }

    /**
     * Reads the committed row of the transaction that won the reservation race.
     * The conflicting insert waits for that transaction to finish, so the row
     * is visible here; a missing row is an invariant failure, not a retry.
     */
    private ClaimCommandIdempotency winner(UUID tenantId, String idempotencyKey, String commandType) {
        return idempotency.find(tenantId, idempotencyKey, commandType)
                .orElseThrow(() -> new IllegalStateException(
                        "Lost the idempotency reservation but the winner's row is not visible"));
    }

    private String json(ClaimNote note) {
        try {
            return objectMapper.writeValueAsString(note);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize the claim note response", ex);
        }
    }
}
