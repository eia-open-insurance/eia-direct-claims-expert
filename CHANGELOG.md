# Changelog

All notable changes to this repository are documented here.

The format is based on Keep a Changelog and the project uses Semantic Versioning.

## [Unreleased]

### Added

- Repository baseline.
- Direct Claims expert slice: `claim` schema for `eia_direct`, create/list/get and status
  transition endpoints under `/internal/v1/claims`, DIRECT book guard, date/reserve
  invariants, UUIDv7 identifiers, optimistic versioning, and JUnit tests.
- Second Direct Claims slice (SPEC 11.4):
  - `claim_injury` and `claim_exposure` with claimant, injury type, body part, ICD-10 codes,
    exposure dates, jurisdiction, severity, version, and add/list/update endpoints.
  - Append-only `claim_reserve_history` with derived current reserves, components, reason,
    authority, effective date, and version; negative, reasonless, and stale changes rejected.
  - Coverage decisions (COVERED/DENIED/DEFENSE_ONLY) with basis, policy provisions,
    allocation, decision date, reviewer; coverage required before OPEN and injury/exposure
    facts required before acceptance.
  - `claim_litigation`, `claim_settlement`, `claim_recovery`, and `claim_lien` with authority
    limits, release/payment instructions, paid-loss recovery caps, and lien resolution.
  - `POST /close` with unresolved-lien and open-reserve closure criteria.
  - `DirectClaimsLiveSmokeTest` against real PostgreSQL and
    `scripts/live-smoke-domain.ps1` through the domain API.
- Transactional outbox production (SPEC 7.5.1):
  - Composite build against `eia-platform` and `com.eia:libs`, Quartz dispatch scheduling
    gated by `eia.outbox.enabled`, and H2 test runtime.
  - `outbox_event` DDL in `schema.sql` and the H2 component-test schema.
  - Canonical events written in the claim transaction: `direct.claim.registered`,
    `direct.claim.updated`, `direct.claim.closed`, `direct.claim.coverage-assessed`,
    `direct.claim.reserve-changed`, and `direct.claim.settlement-authorized`.
  - Payloads carry claim identity, status, version, and reserve amounts as decimal strings
    plus event-specific fields; integration tests cover payloads and rollback for create,
    coverage, reserve, settlement, and close.
- Claim tasks and notes:
  - Idempotent `claim_task` and `claim_note` DDL plus indexes and H2 test schema; task
    numbers and note numbers are unique per tenant, tasks carry a bigint optimistic version,
    and notes are append-only.
  - `GET|POST /api/v1/claims/{claimId}/tasks` and `PATCH .../tasks/{taskId}` (also aliased
    under `/internal/v1`), with title/priority/due-date validation, open-first due-date
    ordering, COMPLETE/CANCELLED transitions, and 409 on a stale task version.
  - `GET|POST /api/v1/claims/{claimId}/notes`, newest first and append-only.
  - Tenant/DIRECT-book scoping with 404 for a claim outside the tenant or book.
  - Outbox events written in the same transaction: `direct.claim.task-created`,
    `direct.claim.task-completed`, `direct.claim.task-updated`, and
    `direct.claim.note-added`; task/note writes bump the claim version so event aggregate
    versions stay monotonic, and rollback tests cover both resources.
  - Optional `Idempotency-Key` on the task and note create routes, scoped by tenant and
    command type and stored in `claim_command_idempotency`. The key is reserved atomically
    with `INSERT ... ON CONFLICT DO NOTHING` in the same transaction as the child row and
    its event; the response is stored on completion, so a same-body retry replays the
    original 201 response with `Idempotent-Replay: true` and writes no new row or event,
    a different body under the same key is 409 `EIA-DIR-4091` / `IDEMPOTENCY_KEY_REUSED`,
    and a simultaneous same-key command replays the winner instead of raising a database
    constraint error.
- Claim assignment:
  - Additive, idempotent `claim` columns `assigned_adjuster VARCHAR(128)`,
    `assigned_team VARCHAR(64)`, `assigned_at TIMESTAMPTZ`, and `assigned_by VARCHAR(128)`
    in the PostgreSQL and H2 schemas.
  - `PATCH /api/v1/claims/{claimId}/assignment` and the `/internal/v1` alias accept
    `{assignedAdjuster, assignedTeam, expectedVersion}`. Adjuster is required (1-128),
    team is optional (at most 64), and the claim version is the optimistic token; a
    negative `expectedVersion` is 400 `VALIDATION_ERROR`.
  - Assignment and reassignment bump the claim version; a stale version is 409, unknown or
    out-of-tenant/non-DIRECT claims are 404, and repeating the same assignment at the
    current version is a 200 no-op with no event.
  - Write `direct.claim.assigned` to the outbox in the same transaction with `claimId`,
    `claimNumber`, `assignedAdjuster`, `assignedTeam`, `assignedAt`, `assignedBy`, and
    `version`. `assignedBy` comes from the canonical `X-EIA-Actor` header and defaults to
    `assignedAdjuster`; the former `X-EIA-User` alias is no longer accepted.
  - Claim list/get projections expose `assignedAdjuster`, `assignedTeam`, `assignedAt`, and
    `assignedBy`; controller and H2 integration tests cover assign/reassign, stale version,
    no-op, validation, tenant/book isolation, event payload, and rollback.
- Claim allocations:
  - Idempotent `claim_allocation` DDL (PostgreSQL and H2) with `id`, `tenant_id`,
    `claim_id` FK, `policy_id`, `policy_year`, `layer VARCHAR(32)`,
    `allocation_pct NUMERIC(9,6)`, `allocated_amount NUMERIC(19,4)`, `created_at`, a
    `(tenant_id, claim_id)` index, and checks for `0 < allocation_pct <= 1` and
    `allocated_amount >= 0`.
  - `GET|POST /api/v1/claims/{claimId}/allocations`; the list is oldest first and returns
    `{items, totalPct, totalAmount}`, and create returns 201.
  - Claim/tenant scoping is 404, the policy must be the claim's own policy (400
    `POLICY_MISMATCH`), the year must be 1900-2200, the layer at most 32 characters, the
    percentage in (0, 1], and the percentages across a claim at most 1 (409
    `ALLOCATION_EXCEEDS_100`). The allocated amount is not capped by the reserve.
  - `direct.claim.allocation-created` is written to the outbox in the same transaction with
    `claimId`, `claimNumber`, `policyId`, `policyYear`, `layer`, `allocationPct`,
    `allocatedAmount`, `totalPct`, and `totalAmount`; allocation writes bump the claim
    version, and integration tests cover the payload and rollback.
- Claim allocation policy erosion:
  - Additive, idempotent `claim_allocation` columns `limit_id UUID`,
    `erosion_status VARCHAR(16) NOT NULL DEFAULT 'NOT_POSTED'`, `erosion_message TEXT`, and
    `erosion_posted_at TIMESTAMPTZ` in the PostgreSQL and H2 schemas, with
    `NOT_POSTED|POSTED|FAILED` and posted-instant consistency checks.
  - `POST /api/v1/claims/{claimId}/allocations` accepts an optional `limitId`; every
    allocation starts at `NOT_POSTED`, and the allocation response exposes `limitId`,
    `erosionStatus`, `erosionMessage`, and `erosionPostedAt`.
  - `PATCH /api/v1/claims/{claimId}/allocations/{allocationId}/erosion` (also under
    `/internal/v1`) takes `{status: POSTED|FAILED, message?}`. POSTED stamps the posting
    instant, FAILED clears it, a same-status repeat is an idempotent 200 no-op, changing an
    already POSTED allocation is 409, and an absent/out-of-tenant allocation is 404. The
    posting updates only the allocation row; it does not bump the claim version or write an
    outbox event.
  - Service, controller, and H2 integration tests cover create with and without a limit,
    posted/failed transitions, retry after failure, idempotency, tenant/claim isolation, and
    the allocation DTO.
- Settlement payment linkage:
  - Additive, idempotent `claim_settlement` columns `payment_id UUID` and
    `payment_linked_at TIMESTAMPTZ` in the PostgreSQL and H2 schemas.
  - `PATCH /api/v1/claims/{claimId}/settlements/{settlementId}/payment` (also under
    `/internal/v1`) takes `{paymentId}`. Only an AUTHORIZED or EXECUTED settlement is linkable;
    a PROPOSED or REJECTED settlement is 409 `SETTLEMENT_NOT_LINKABLE`, and an absent,
    out-of-tenant, or another-claim settlement is 404.
  - A non-null `paymentId` stores the external Payment aggregate id and stamps
    `paymentLinkedAt`; null clears both. Repeating the current link is a 200 no-op. A real
    link or unlink bumps the claim and settlement versions.
  - The settlement DTO exposes `paymentId` and `paymentLinkedAt`.
  - `direct.claim.settlement-payment-linked` is written to the outbox in the same transaction
    with `claimId`, `settlementId`, `settlementNumber`, `paymentId`, and `linkedAt`; an unlink
    omits the two null payment fields. Service, controller, and H2 integration tests cover
    link/unlink, invalid status, tenant isolation, the DTO, event payload, and idempotency.
- Claim list claimant filter: `GET /internal/v1/claims` accepts an optional
  `claimantPartyId` UUID alongside `status`. The tenant and DIRECT book scope stay
  mandatory, the two optional filters combine, and results remain newest first by
  `created_at` with `claim_number` as the tie-break. Repository, service, and controller
  tests cover the filter, tenant isolation, the unchanged unfiltered list, and ordering.
- Litigation damages:
  - Additive, idempotent `claim_litigation` columns `damages_sought NUMERIC(19,4)` and
    `damages_currency CHAR(3)` in the PostgreSQL and H2 schemas, with a both-or-neither and
    strictly-positive check.
  - `POST|PATCH .../litigation` accept optional `damagesSought` and `damagesCurrency`.
    Create requires both or neither; PATCH omitting both keeps the stored pair. A present
    amount must be greater than zero and the currency is a three-letter ISO code.
  - The litigation DTO exposes `damagesSought` (decimal string) and `damagesCurrency`.
  - `direct.claim.litigation-created` (new name; catalog follow-up) and
    `direct.claim.litigation-updated` (previously planned) are written to the outbox in the
    same transaction as the matter and the claim version bump, carrying the matter and
    damages when present. Service, controller, and H2 integration tests cover create/update
    with and without damages, pair/positive validation, tenant isolation, DDL checks, and
    payloads.

### Fixed

- Task and note `Idempotency-Key` reservation is atomic. The key row is inserted with
  `INSERT ... ON CONFLICT DO NOTHING` before the command executes, so two simultaneous
  same-key requests do not return a raw database constraint error. The losing reservation
  reads the winner's committed row and replays an identical canonical body; a different
  body is 409 `EIA-DIR-4091`. `response_status`/`response_body` are nullable during the
  reservation window, with an idempotent PostgreSQL `DROP NOT NULL` migration and a
  two-thread H2 concurrency test.
- Claim assignment rejects a negative `expectedVersion` with 400 `VALIDATION_ERROR`
  instead of treating it as a stale-version 409, in both bean validation and the service.
- Claim assignment accepts only the canonical `X-EIA-Actor` header; the `X-EIA-User` alias
  was removed and the controller test now asserts that it is ignored.
