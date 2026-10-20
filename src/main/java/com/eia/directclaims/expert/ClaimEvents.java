package com.eia.directclaims.expert;

import com.eia.platform.libs.events.Book;
import com.eia.platform.libs.events.EventActor;
import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.events.EventIds;
import com.eia.platform.libs.events.EventMetadata;
import com.eia.platform.libs.events.EventSubject;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Event factory for the Direct Claims vertical.
 *
 * <p>Event names are the canonical catalog names in
 * {@code eia-contracts/asyncapi/event-catalog.yaml} and
 * {@code direct-claims.yaml}. Three slice names requested for this rollout
 * differ from the catalog and are mapped here instead of inventing new
 * names:</p>
 *
 * <ul>
 *   <li>{@code direct.claim.created} -&gt; {@code direct.claim.registered}</li>
 *   <li>{@code direct.claim.coverage-decided} -&gt;
 *       {@code direct.claim.coverage-assessed}</li>
 *   <li>{@code direct.claim.settlement-approved} -&gt;
 *       {@code direct.claim.settlement-authorized}</li>
 * </ul>
 *
 * <p>Every event describes the Claim aggregate: the claim id is the aggregate
 * id and the partition key, so the outbox dispatcher preserves per-claim
 * order. Payloads carry the claim identity, status, row version, and reserve
 * amounts as decimal strings (SPEC 7.6), plus the event-specific members.</p>
 *
 * <p>Claim tasks and notes are children of the same aggregate. Their writes bump
 * the claim row version too, so {@code direct.claim.task-created},
 * {@code direct.claim.task-completed}, {@code direct.claim.task-updated}, and
 * {@code direct.claim.note-added} stay monotonic with the claim lifecycle
 * events. These four names are new in this slice and are not yet present in
 * {@code eia-contracts/asyncapi}; the contracts repository is owned separately
 * and the gap is reported for catalog follow-up.</p>
 *
 * <p>{@code direct.claim.allocation-created} is new in the allocation slice;
 * like the task/note/assignment names it is not yet in the catalog and is
 * reported as a catalog follow-up.</p>
 *
 * <p>Claim rows start at version 0 for API compatibility, while the canonical
 * envelope requires {@code aggregateVersion >= 1}. The envelope version is
 * therefore the claim's row version after the change plus one, matching the
 * Party and Cession experts (create -&gt; 1, first update -&gt; 2).</p>
 */
final class ClaimEvents {
    static final String REGISTERED = "direct.claim.registered";
    static final String UPDATED = "direct.claim.updated";
    static final String COVERAGE_ASSESSED = "direct.claim.coverage-assessed";
    static final String RESERVE_CHANGED = "direct.claim.reserve-changed";
    static final String SETTLEMENT_AUTHORIZED = "direct.claim.settlement-authorized";
    static final String CLOSED = "direct.claim.closed";
    static final String ASSIGNED = "direct.claim.assigned";
    static final String TASK_CREATED = "direct.claim.task-created";
    static final String TASK_COMPLETED = "direct.claim.task-completed";
    static final String TASK_UPDATED = "direct.claim.task-updated";
    static final String NOTE_ADDED = "direct.claim.note-added";
    static final String ALLOCATION_CREATED = "direct.claim.allocation-created";

    private static final Book EVENT_BOOK = Book.DIRECT;
    private static final String VERTICAL = "direct";
    private static final String AGGREGATE_TYPE = "Claim";
    private static final String SOURCE_BUILD = "eia-direct-claims-expert@0.1.0-SNAPSHOT";
    private static final BigDecimal ZERO = new BigDecimal("0.0000");

    private ClaimEvents() {
    }

    /**
     * Registration event for a newly created claim. The claim row is still at
     * version 0, so the envelope aggregate version is 1.
     */
    static EventEnvelope registered(Claim claim, Instant now) {
        Map<String, Object> data = identity(claim, claim.version());
        data.put("claimType", claim.claimType());
        data.put("lossDate", claim.lossDate());
        data.put("reportDate", claim.reportDate());
        if (claim.causeCode() != null) {
            data.put("causeCode", claim.causeCode());
        }
        data.put("settlementAuthorityAmount", money(claim.settlementAuthorityAmount()));
        data.put("settlementAuthorityCurrency", claim.settlementAuthorityCurrency());
        return envelope(claim, REGISTERED, claim.version() + 1L, now, data);
    }

    /**
     * Status transition. The claim row version has already been incremented by
     * the repository, so {@code after.version()} is the new row version.
     */
    static EventEnvelope statusUpdated(Claim before, Claim after, Instant now) {
        Map<String, Object> data = identity(after, after.version());
        data.put("changedFields", List.of("status"));
        data.put("from", before.status());
        data.put("to", after.status());
        return envelope(after, UPDATED, after.version() + 1L, now, data);
    }

    /**
     * Final coverage decision. Recording a decision bumps the claim version, so
     * the caller passes the post-change claim version.
     */
    static EventEnvelope coverageAssessed(Claim claim, CoverageDecision decision,
                                          int claimVersion, Instant now) {
        Map<String, Object> data = identity(claim, claimVersion);
        data.put("coverageDecisionId", decision.id());
        data.put("position", decision.position());
        data.put("coverageStatus", decision.status());
        data.put("basis", decision.basis());
        if (decision.policyProvisions() != null) {
            data.put("policyProvisions", decision.policyProvisions());
        }
        if (decision.allocationMethod() != null) {
            data.put("allocationMethod", decision.allocationMethod());
        }
        data.put("allocationPct", money(decision.allocationPct()));
        data.put("decisionDate", decision.decisionDate());
        data.put("reviewerPartyId", decision.reviewerPartyId());
        data.put("coverageVersion", decision.version());
        return envelope(claim, COVERAGE_ASSESSED, claimVersion + 1L, now, data);
    }

    /**
     * Append-only reserve change. {@code previousAmount} is the prior current
     * amount for the component, and {@code totalCurrentReserve} is the new
     * derived total across all current components.
     */
    static EventEnvelope reserveChanged(Claim claim, ReserveHistoryEntry entry,
                                        BigDecimal previousAmount, BigDecimal totalCurrentReserve,
                                        Instant now) {
        BigDecimal previous = previousAmount == null ? ZERO : previousAmount;
        Map<String, Object> data = identity(claim, entry.version());
        data.put("currentReserveAmount", money(totalCurrentReserve));
        data.put("reserveEntryId", entry.id());
        data.put("component", entry.component());
        data.put("amount", money(entry.amount()));
        data.put("currency", entry.currency());
        data.put("effectiveDate", entry.effectiveDate());
        data.put("reasonCode", entry.reasonCode());
        if (entry.reasonText() != null) {
            data.put("reasonText", entry.reasonText());
        }
        if (entry.authority() != null) {
            data.put("authority", entry.authority());
        }
        data.put("sequence", entry.sequence());
        data.put("previousAmount", money(previous));
        data.put("deltaAmount", money(entry.amount().subtract(previous)));
        data.put("totalCurrentReserveAmount", money(totalCurrentReserve));
        return envelope(claim, RESERVE_CHANGED, entry.version() + 1L, now, data);
    }

    /**
     * Settlement authorization. The claim version is bumped as part of the
     * authorization so the event remains monotonic per claim.
     */
    static EventEnvelope settlementAuthorized(Claim claim, Settlement settlement,
                                              int claimVersion, Instant now) {
        Map<String, Object> data = identity(claim, claimVersion);
        data.put("settlementId", settlement.id());
        data.put("settlementNumber", settlement.settlementNumber());
        data.put("settlementAmount", money(settlement.amount()));
        data.put("settlementCurrency", settlement.currency());
        data.put("settlementStatus", settlement.status());
        data.put("settlementVersion", settlement.version());
        data.put("structure", settlement.structure());
        if (settlement.releaseScope() != null) {
            data.put("releaseScope", settlement.releaseScope());
        }
        data.put("approvalAuthority", settlement.approvalAuthority());
        data.put("authorizedBy", settlement.authorizedBy());
        data.put("authorizedAt", settlement.authorizedAt());
        return envelope(claim, SETTLEMENT_AUTHORIZED, claimVersion + 1L, now, data);
    }

    /**
     * Claim closure. {@code before} supplies the previous status and
     * {@code after} supplies the CLOSED row and its new version.
     */
    static EventEnvelope closed(Claim before, Claim after, Instant now) {
        Map<String, Object> data = identity(after, after.version());
        data.put("changedFields", List.of("status"));
        data.put("from", before.status());
        data.put("to", after.status());
        data.put("closedAt", now);
        data.put("finalReserveAmount", money(after.currentReserveAmount()));
        return envelope(after, CLOSED, after.version() + 1L, now, data);
    }

    /**
     * Claim assignment or reassignment. The repository has already incremented
     * the claim row version, so {@code claim.version()} is the post-write
     * version and the envelope aggregate version is one greater.
     */
    static EventEnvelope assigned(Claim claim, Instant now) {
        Map<String, Object> data = identity(claim, claim.version());
        data.put("assignedAdjuster", claim.assignedAdjuster());
        data.put("assignedTeam", claim.assignedTeam());
        data.put("assignedAt", claim.assignedAt());
        data.put("assignedBy", claim.assignedBy());
        return envelope(claim, ASSIGNED, claim.version() + 1L, now, data);
    }

    /**
     * Task creation. The task starts at version 1; the claim row has already
     * been bumped, so {@code claimVersion} is the post-write claim version.
     */
    static EventEnvelope taskCreated(Claim claim, ClaimTask task, int claimVersion, Instant now) {
        Map<String, Object> data = identity(claim, claimVersion);
        data.put("claimStatus", claim.status());
        task(data, task);
        return envelope(claim, TASK_CREATED, claimVersion + 1L, now, data);
    }

    /**
     * Task status change. COMPLETE uses the lifecycle-specific
     * {@code task-completed}; a cancellation or reopen uses {@code task-updated}
     * with the before/after status.
     */
    static EventEnvelope taskStatusChanged(Claim claim, ClaimTask before, ClaimTask after,
                                           int claimVersion, Instant now) {
        Map<String, Object> data = identity(claim, claimVersion);
        data.put("claimStatus", claim.status());
        task(data, after);
        data.put("changedFields", List.of("status"));
        data.put("from", before.status());
        data.put("to", after.status());
        String eventType = "COMPLETE".equals(after.status()) ? TASK_COMPLETED : TASK_UPDATED;
        return envelope(claim, eventType, claimVersion + 1L, now, data);
    }

    /**
     * Append-only note. Notes have no row version of their own; the claim
     * version keeps the event ordered with the rest of the claim stream.
     */
    static EventEnvelope noteAdded(Claim claim, ClaimNote note, int claimVersion, Instant now) {
        Map<String, Object> data = identity(claim, claimVersion);
        data.put("noteId", note.id());
        data.put("noteNumber", note.noteNumber());
        data.put("noteType", note.noteType());
        data.put("body", note.body());
        data.put("author", note.author());
        data.put("createdAt", note.createdAt());
        return envelope(claim, NOTE_ADDED, claimVersion + 1L, now, data);
    }

    /**
     * Claim allocation by policy year and layer. The create bumps the claim
     * version, so {@code claimVersion} is the post-write claim version and the
     * envelope aggregate version is one greater. The payload carries the claim
     * identity plus the allocation and the claim's new allocation totals.
     */
    static EventEnvelope allocationCreated(Claim claim, ClaimAllocation allocation,
                                           int claimVersion, BigDecimal totalPct,
                                           BigDecimal totalAmount, Instant now) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("claimId", claim.id());
        data.put("claimNumber", claim.claimNumber());
        data.put("policyId", allocation.policyId());
        data.put("policyYear", allocation.policyYear());
        data.put("layer", allocation.layer());
        data.put("allocationPct", money(allocation.allocationPct()));
        data.put("allocatedAmount", money(allocation.allocatedAmount()));
        data.put("totalPct", money(totalPct));
        data.put("totalAmount", money(totalAmount));
        return envelope(claim, ALLOCATION_CREATED, claimVersion + 1L, now, data);
    }

    private static void task(Map<String, Object> data, ClaimTask task) {
        data.put("taskId", task.id());
        data.put("taskNumber", task.taskNumber());
        data.put("title", task.title());
        if (task.description() != null) {
            data.put("description", task.description());
        }
        data.put("status", task.status());
        data.put("priority", task.priority());
        data.put("dueDate", task.dueDate());
        if (task.assignedTo() != null) {
            data.put("assignedTo", task.assignedTo());
        }
        data.put("taskVersion", task.version());
        data.put("taskCreatedAt", task.createdAt());
        data.put("taskUpdatedAt", task.updatedAt());
        if (task.completedAt() != null) {
            data.put("completedAt", task.completedAt());
        }
    }

    /**
     * Identity and reserve context shared by every Direct Claims event. Money
     * is a decimal string, never a JSON number (SPEC 7.6).
     */
    private static Map<String, Object> identity(Claim claim, int version) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("claimId", claim.id());
        data.put("claimNumber", claim.claimNumber());
        data.put("policyId", claim.policyId());
        data.put("claimantPartyId", claim.claimantPartyId());
        data.put("businessBook", claim.businessBook());
        data.put("status", claim.status());
        data.put("version", version);
        data.put("reserveAmount", money(claim.reserveAmount()));
        data.put("currentReserveAmount", money(claim.currentReserveAmount()));
        data.put("reserveCurrency", claim.reserveCurrency());
        return data;
    }

    private static EventEnvelope envelope(Claim claim, String eventType, long aggregateVersion,
                                          Instant now, Map<String, Object> data) {
        UUID eventId = EventIds.deterministic(
                claim.tenantId(), AGGREGATE_TYPE, claim.id(), aggregateVersion, eventType);
        return EventEnvelope.builder()
                .eventId(eventId)
                .eventType(eventType)
                .eventVersion(1)
                .occurredAt(now)
                .recordedAt(now)
                .tenantId(claim.tenantId())
                .book(EVENT_BOOK)
                .vertical(VERTICAL)
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(claim.id())
                .aggregateVersion(aggregateVersion)
                .sequence(0)
                .actor(EventActor.system("eia-direct-claims-expert"))
                .subject(EventSubject.of(VERTICAL, "claim", claim.id(), claim.claimNumber()))
                .links(List.of())
                .data(data)
                .metadata(EventMetadata.v1(SOURCE_BUILD))
                .build();
    }

    private static String money(BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }
}
