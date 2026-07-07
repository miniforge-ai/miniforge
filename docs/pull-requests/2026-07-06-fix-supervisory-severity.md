<!--
  Title: Migrate the policy-summary histogram to canonical severity
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: migrate the policy-summary histogram to canonical severity

Branch: `fix/supervisory-severity`

## Summary

Final step of governance-audit Finding 6 (miniforge side). The
`:policy/summary` histogram (`PolicyCounts`) was the last place still keyed by
the legacy `:major`/`:minor` vocabulary. Migrate it to the canonical 5-level
scale (`:critical/:high/:medium/:low/:info`), matching `:violation/severity`
(which was already 5-level).

This was a real misalignment, not just cosmetic: the histogram counts violations
by severity, but violations carry `:high`/`:medium`/`:low` while the buckets were
`:major`/`:minor` — so a `:high` violation had no bucket to land in.

## Changes

- `supervisory-state/schema.clj`: `PolicyCounts` keys `:major`/`:minor` →
  `:high`/`:medium`/`:low`.
- `golden_fixtures.clj` + `accumulator_test.clj`: the `:policy/summary` fixtures.
- Regenerated `contracts/supervisory-entities/golden/pr.transit.json` via
  `bb fixtures:supervisory` (now `{:critical :high :medium :low :info :total}`).

`evidence-bundle` and `supervisory-state` `violation-severities` were already the
5-level canonical set, so no change there.

## Cross-repo

The golden fixtures are vendored by **miniforge-control** (Rust
`supervisory-entities` crate: `PrPolicySummary { major, minor }` + its own golden
copy). A companion miniforge-control PR migrates that struct to
`{ high, medium, low }` and re-vendors the regenerated golden. This PR is the
source of the contract; the miniforge-control PR follows.

## Test plan

- `supervisory-state` + `schema` suites: 71 tests, 270 assertions, 0 failures
  (incl. the regenerated golden-fixtures round-trip).
- `bb pre-commit` green; `bb poly:check` clean.

## Related

- Governance audit Finding 6 (final in-repo step). Depends on #1382/#1383/#1384.
