# eia-direct-claims-expert

- GitHub organization: `eia-open-insurance`
- Repository URL: https://github.com/eia-open-insurance/eia-direct-claims-expert
- Type: Spring Boot service
- Deployable: Yes
- Owner: Direct Claims
- Runtime: Java 22, Spring Boot, Spring Batch, Quartz
- Data store: eia_direct (claim table)
- Depends on: eia-contracts, eia-platform/libs

## Purpose

Direct Claims persistence, invariants, read models, batch jobs, Quartz schedules, and outbox/inbox. Direct claims: FNOL, coverage, injury, reserves, litigation, settlement, and recovery.

## Responsibilities

- Keep this repository focused on one deployable component or one platform concern.
- Follow the repository standard in `../CONVENTIONS.md` and the specification in `../../SPEC.md`.
- Publish and consume contracts through `eia-contracts`.
- Maintain README, CODEOWNERS, SECURITY, CHANGELOG, tests, and local verification.
- Contain no business logic in shared libraries or front-end remotes.

## Local run

Prerequisites: JDK 22, PostgreSQL 17, and database `eia_direct` (`postgres`/`postgres` by default).

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-22.0.2.9-hotspot'
.\gradlew build
.\gradlew bootRun
```

The service listens on **8087**. `src/main/resources/schema.sql` creates the claim tables,
sequences, indexes, the book/date/reserve check constraints, and the transactional outbox on
startup. It is idempotent and additive: the second slice adds injuries, exposures, reserve
history, coverage decisions, litigation, settlements, recoveries, liens, and `outbox_event`;
the third slice adds claim tasks and append-only diary notes; the fourth slice adds claim
assignment fields to `claim`.

Internal endpoints (all require `X-EIA-Book: DIRECT`; any other book returns 404):

- `GET /internal/v1/claims?tenantId={uuid}&status={status}`
- `GET /internal/v1/claims/{id}?tenantId={uuid}`
- `POST /internal/v1/claims`
- `PATCH /internal/v1/claims/{id}/status`
- `POST /internal/v1/claims/{id}/close`
- `PATCH /api/v1/claims/{claimId}/assignment` (also under `/internal/v1`)
- `GET|POST /internal/v1/claims/{id}/injuries`, `PATCH /internal/v1/claims/{id}/injuries/{injuryId}`
- `GET|POST /internal/v1/claims/{id}/exposures`, `PATCH /internal/v1/claims/{id}/exposures/{exposureId}`
- `GET|POST /internal/v1/claims/{id}/reserves`
- `GET|POST /internal/v1/claims/{id}/coverage`
- `GET|POST /internal/v1/claims/{id}/litigation`, `PATCH .../litigation/{litigationId}`
- `GET|POST /internal/v1/claims/{id}/settlements`, `POST .../settlements/{settlementId}/approve`,
  `POST .../settlements/{settlementId}/execute`
- `GET|POST /internal/v1/claims/{id}/recoveries`, `PATCH .../recoveries/{recoveryId}`
- `GET|POST /internal/v1/claims/{id}/liens`, `PATCH .../liens/{lienId}`
- `GET|POST /internal/v1/claims/{id}/tasks`, `PATCH .../tasks/{taskId}`
- `GET|POST /internal/v1/claims/{id}/notes`

The task and note routes are also exposed on the public prefix
`/api/v1/claims/{claimId}/tasks` and `/api/v1/claims/{claimId}/notes`. `GET` accepts
either `?tenantId={uuid}` or `X-EIA-Tenant`; writes take `X-EIA-Tenant`.

Claim assignment uses `PATCH /api/v1/claims/{claimId}/assignment` (or the internal alias)
with `{ "assignedAdjuster": "adjuster@example.com", "assignedTeam": "CASUALTY",
"expectedVersion": 3 }`. `assignedAdjuster` is required (1-128 characters), `assignedTeam`
is optional (at most 64), and `expectedVersion` is required. A real assignment or
reassignment replaces the fields, bumps the claim version, and writes
`direct.claim.assigned`; repeating the same adjuster and team at the current version is a
200 no-op with no event. A stale version is 409, and an unknown, foreign-tenant, or
non-DIRECT claim is 404. `assignedBy` is the optional canonical `X-EIA-User` header (the
legacy `X-EIA-Actor` header is also accepted), falling back to `assignedAdjuster`. Claim
list/get responses include `assignedAdjuster`, `assignedTeam`, `assignedAt`, and
`assignedBy`.

Tasks are returned open-first ordered by due date, then cancelled, then completed
(newest completion first). A task starts at version 1 and `PATCH` takes
`{ "status": "COMPLETE", "version": 1 }`; a stale version is 409. Repeating the
current status is an idempotent no-op. Notes are append-only and returned newest
first; there is no update or delete route. Both resources are tenant- and
claim-scoped, so a claim outside the tenant or the DIRECT book is 404.

The task and note create routes accept an optional `Idempotency-Key` (trimmed,
1-64 printable ASCII characters; a UUID is recommended). The key is scoped by
tenant and command type (`TASK_CREATE` or `NOTE_CREATE`) and stored in
`claim_command_idempotency` with a canonical request hash and the original 201
response body. A retry with the same key and the same canonical body returns the
stored 201 body with `Idempotent-Replay: true` and writes no second row or outbox
event. Reusing the key with a different body is 409 `application/problem+json`
with code `EIA-DIR-4091` and category `IDEMPOTENCY_KEY_REUSED`. Without a key the
create behaviour is unchanged.

Money is serialized as a decimal string. The claim's current reserve is derived from the
current rows of `claim_reserve_history`; `reserve_amount` is the initial reserve seeded at
registration.

## Transactional outbox

Claim writes append a canonical domain event to `outbox_event` in the same transaction as the
aggregate change (SPEC 7.5.1), so a rollback leaves neither the change nor the event behind.
The service publishes the names from `eia-contracts/asyncapi/event-catalog.yaml` and
`direct-claims.yaml`:

| Trigger | Event type |
| --- | --- |
| `POST /internal/v1/claims` | `direct.claim.registered` |
| Status change (except CLOSED) | `direct.claim.updated` |
| Status change to CLOSED or `POST .../close` | `direct.claim.closed` |
| `PATCH .../assignment` | `direct.claim.assigned` |
| `POST .../coverage` | `direct.claim.coverage-assessed` |
| `POST .../reserves` | `direct.claim.reserve-changed` |
| `POST .../settlements/{id}/approve` | `direct.claim.settlement-authorized` |
| `POST .../tasks` | `direct.claim.task-created` |
| `PATCH .../tasks/{taskId}` to COMPLETE | `direct.claim.task-completed` |
| `PATCH .../tasks/{taskId}` to CANCELLED or reopened | `direct.claim.task-updated` |
| `POST .../notes` | `direct.claim.note-added` |

The requested slice names `direct.claim.created`, `direct.claim.coverage-decided`, and
`direct.claim.settlement-approved` are not in the catalog; the catalog names above are
published instead. The task, note, and assignment event names are new in this slice and are
not yet in `eia-contracts/asyncapi` (the contracts repository is owned separately); they are
reported as a catalog follow-up. Every payload carries `claimId`, `claimNumber`, `policyId`,
`claimantPartyId`, `businessBook`, `status`, `version`, and the reserve amounts as decimal
strings (`reserveAmount`, `currentReserveAmount`, `reserveCurrency`), plus event-specific
members. Task payloads add `taskId`, `taskNumber`, `title`, `status` (the task status),
`claimStatus` (the parent claim status), `priority`, `dueDate`, `assignedTo`, and `taskVersion`;
note payloads add `noteId`, `noteNumber`, `noteType`, `body`, and `author`; assignment
payloads add `assignedAdjuster`, `assignedTeam`, `assignedAt`, and `assignedBy`. Task, note,
and assignment writes bump the claim version, so their aggregate version is the post-write
claim version plus one.

Outbox rows are always written. HTTP dispatch and the Quartz job start only when
`eia.outbox.enabled=true`; configure the subscriber endpoint with `EIA_OUTBOX_ENDPOINT`.
Retry, lease, batch, and cadence values live under `eia.outbox` in `application.yml`.

## Verification

- `.\gradlew build` - unit, controller, and H2 outbox/scheduling tests (the `live` tag is
  excluded).
- `.\gradlew test -Plive` - the live PostgreSQL smoke (`DirectClaimsLiveSmokeTest`).
- `pwsh ./scripts/live-smoke-domain.ps1` - full journey through the domain API with both
  services running; it creates a fresh tenant and exercises injury, coverage, reserve,
  settlement, and recovery including the negative cases.

## Notes

Writes data; does not decide business outcomes.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
