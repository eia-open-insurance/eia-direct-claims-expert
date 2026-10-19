# Changelog

All notable changes to this repository are documented here.

The format is based on Keep a Changelog and the project uses Semantic Versioning.

## [Unreleased]

### Added

- Repository baseline.
- Direct Claims expert slice: `claim` schema for `eia_direct`, create/list/get and status
  transition endpoints under `/internal/v1/claims`, DIRECT book guard, date/reserve
  invariants, UUIDv7 identifiers, optimistic versioning, and JUnit tests.
