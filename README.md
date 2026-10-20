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
history, coverage decisions, litigation, settlements, recoveries, liens, and `outbox_event`.

Internal endpoints (all require `X-EIA-Book: DIRECT`; any other book returns 404):

- `GET /internal/v1/claims?tenantId={uuid}&status={status}`
- `GET /internal/v1/claims/{id}?tenantId={uuid}`
- `POST /internal/v1/claims`
- `PATCH /internal/v1/claims/{id}/status`
- `POST /internal/v1/claims/{id}/close`
- `GET|POST /internal/v1/claims/{id}/injuries`, `PATCH /internal/v1/claims/{id}/injuries/{injuryId}`
- `GET|POST /internal/v1/claims/{id}/exposures`, `PATCH /internal/v1/claims/{id}/exposures/{exposureId}`
- `GET|POST /internal/v1/claims/{id}/reserves`
- `GET|POST /internal/v1/claims/{id}/coverage`
- `GET|POST /internal/v1/claims/{id}/litigation`, `PATCH .../litigation/{litigationId}`
- `GET|POST /internal/v1/claims/{id}/settlements`, `POST .../settlements/{settlementId}/approve`,
  `POST .../settlements/{settlementId}/execute`
- `GET|POST /internal/v1/claims/{id}/recoveries`, `PATCH .../recoveries/{recoveryId}`
- `GET|POST /internal/v1/claims/{id}/liens`, `PATCH .../liens/{lienId}`

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
| `POST .../coverage` | `direct.claim.coverage-assessed` |
| `POST .../reserves` | `direct.claim.reserve-changed` |
| `POST .../settlements/{id}/approve` | `direct.claim.settlement-authorized` |

The requested slice names `direct.claim.created`, `direct.claim.coverage-decided`, and
`direct.claim.settlement-approved` are not in the catalog; the catalog names above are
published instead. Every payload carries `claimId`, `claimNumber`, `policyId`,
`claimantPartyId`, `businessBook`, `status`, `version`, and the reserve amounts as decimal
strings (`reserveAmount`, `currentReserveAmount`, `reserveCurrency`), plus event-specific
members.

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
