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
 * Claim task commands and queries.
 *
 * <p>A task write is part of the Claim aggregate transaction: it bumps the
 * parent claim version (keeping the per-claim event stream monotonic) and
 * appends its event to the outbox in the same transaction. The task's own
 * version is the optimistic concurrency token exposed by PATCH; a stale token
 * is 409 and a command that repeats the current status is a no-op that writes
 * no event.</p>
 */
@Service
public class ClaimTaskService {
    private final ClaimTaskRepository repository;
    private final ClaimRepository claimRepository;
    private final ClaimLookup claims;
    private final OutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public ClaimTaskService(ClaimTaskRepository repository,
                            ClaimRepository claimRepository,
                            ClaimLookup claims,
                            OutboxRepository outbox) {
        this(repository, claimRepository, claims, outbox, Clock.systemUTC());
    }

    ClaimTaskService(ClaimTaskRepository repository,
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

    public List<ClaimTask> list(UUID tenantId, UUID claimId) {
        claims.require(tenantId, claimId);
        return repository.findAll(tenantId, claimId);
    }

    @Transactional
    public ClaimTask create(UUID tenantId, UUID claimId, CreateTaskRequest request) {
        Claim claim = claims.require(tenantId, claimId);
        String title = Vocab.requiredText(request.title(), "title", 200);
        String description = Vocab.optionalText(request.description(), 4000);
        LocalDate dueDate = Vocab.requiredDate(request.dueDate(), "dueDate");
        String priority = Vocab.normalize(Vocab.TASK_PRIORITIES, request.priority(), "priority");
        String assignedTo = Vocab.optionalText(request.assignedTo(), 120);

        Instant now = Instant.now(clock);
        String taskNumber = String.format("TSK-%d-%07d", LocalDate.now(clock).getYear(),
                repository.nextTaskSequence());
        ClaimTask task = repository.insert(UuidV7.next(), tenantId, claimId, taskNumber, title,
                description, dueDate, priority, assignedTo, now);
        int claimVersion = claimRepository.bumpVersion(tenantId, claimId, claim.version(), now);
        outbox.insert(OutboxEvent.fromEnvelope(ClaimEvents.taskCreated(claim, task, claimVersion, now)));
        return task;
    }

    @Transactional
    public ClaimTask changeStatus(UUID tenantId,
                                  UUID claimId,
                                  UUID taskId,
                                  TaskStatusChangeRequest request) {
        Claim claim = claims.require(tenantId, claimId);
        ClaimTask current = repository.findById(tenantId, claimId, taskId)
                .orElseThrow(() -> RecordNotFoundException.of("Claim task", taskId));
        String next = Vocab.normalize(Vocab.TASK_STATUSES, request.status(), "status");
        if (request.version() == null) {
            throw new ClaimValidationException("version is required");
        }
        long expected = request.version();
        if (expected != current.version()) {
            throw new ClaimConflictException("Claim task " + taskId + " version " + expected
                    + " does not match current version " + current.version());
        }
        if (next.equals(current.status())) {
            return current;
        }
        requireTransition(current.status(), next);

        Instant now = Instant.now(clock);
        int claimVersion = claimRepository.bumpVersion(tenantId, claimId, claim.version(), now);
        ClaimTask after = repository.updateStatus(tenantId, claimId, taskId, next, expected, now)
                .orElseThrow(() -> new ClaimConflictException("Claim task " + taskId
                        + " was modified concurrently or version " + expected + " is stale"));
        outbox.insert(OutboxEvent.fromEnvelope(
                ClaimEvents.taskStatusChanged(claim, current, after, claimVersion, now)));
        return after;
    }

    /**
     * A task may be completed or cancelled while open, and either state may be
     * reopened. Cancelling a completed task (or completing a cancelled one)
     * requires an explicit reopen first.
     */
    private static void requireTransition(String current, String next) {
        boolean allowed = switch (current) {
            case "OPEN" -> next.equals("COMPLETE") || next.equals("CANCELLED");
            case "COMPLETE", "CANCELLED" -> next.equals("OPEN");
            default -> false;
        };
        if (!allowed) {
            throw new ClaimValidationException(
                    "Illegal claim task status transition " + current + " -> " + next);
        }
    }
}
