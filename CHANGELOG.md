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
    command type and stored in `claim_command_idempotency`. A same-body retry replays the
    stored 201 response with `Idempotent-Replay: true` and writes no new row or event; a
    different body under the same key is 409 `EIA-DIR-4091` / `IDEMPOTENCY_KEY_REUSED`.
