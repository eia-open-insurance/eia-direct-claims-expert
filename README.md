# eia-direct-expert

- GitHub organization: `eia-platform`
- Repository URL: https://github.com/eia-platform/eia-direct-expert
- Type: Spring Boot service
- Deployable: Yes
- Owner: Direct
- Runtime: Java 22, Spring Boot, Spring Batch, Quartz
- Data store: eia_direct (ent, ref, audit schemas)
- Depends on: eia-contracts, eia-platform-libs

## Purpose

Direct persistence, invariants, read models, batch jobs, Quartz schedules, and outbox/inbox. Direct claims: FNOL, coverage, injury, reserves, litigation, settlement, and recovery.

## Responsibilities

- Keep this repository focused on one deployable component or one platform concern.
- Follow the repository standard in `../CONVENTIONS.md` and the specification in `../../SPEC.md`.
- Publish and consume contracts through `eia-contracts`.
- Maintain README, CODEOWNERS, SECURITY, CHANGELOG, tests, and CI.
- Contain no business logic in shared libraries or front-end remotes.

## Required checks

- Lint and format
- Unit tests with 100% business-logic coverage where applicable
- Contract tests
- Integration or Playwright tests where applicable
- Security scan and SBOM
- Build and publish

## Notes

Writes data; does not decide business outcomes.

