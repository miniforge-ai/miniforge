<!--
  Title: Canonical severity enum in schema
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: canonical severity enum in schema

Branch: `fix/canonical-severity-enum`

## Summary

First step of governance-audit Finding 6 (`miniforge-governance-audit-2026-07-05.md`):
one severity vocabulary, defined once, schema-enforced.

Severity is spelled several ways across the stack: packs use
`:critical/:major/:minor/:info`; supervisory, evidence-bundle, and the schema
component's own `violation-severities` use `:info/:low/:medium/:high/:critical`;
the (now-deleted, PR1) deploy cascade used a third set. A violation's severity is
the severity of the rule it violates — one axis, one scale — so the split forces
an unvalidated mapping at every boundary, which is the bug this finding names.

This PR establishes the single source of truth in the `schema` component (the
codebase's shared-schema home, 35 dependents). It is additive: nothing is
migrated onto it yet, so no behavior changes.

Chosen vocabulary (per decision): the 5-level `:info/:low/:medium/:high/:critical`
— the industry-standard scale, already the runtime `:violation/severity` scale,
and what external consumers expect.

## Changes

`schema/core` + re-exported via `schema/interface`:

- `severities` — `[:critical :high :medium :low :info]`, most to least severe.
- `Severity` — Malli enum; also registered as `:severity`.
- `severity-order` — `{severity → rank}`, derived from `severities` so it cannot
  drift from the enum.
- `normalize-severity` — coerces legacy `:major → :high`, `:minor → :low`;
  canonical/other values unchanged. The bridge that lets producers/readers
  migrate incrementally without an unvalidated mismatch.
- `compare-severity` / `more-severe` — ordering helpers (these already existed,
  duplicated, in `policy-pack/core`; they move here as the shared authority).

## Follow-ups (this is step 1 of a series)

- Migrate `policy-pack` to `schema/severities` (RuleSeverity, `severity-order`,
  mdc-compiler emitting `:high`/`:low`, regenerate the compiled standards pack).
- Migrate `supervisory-state` / `evidence-bundle` `violation-severities` copies
  and the `:policy/summary` histogram (`:major`/`:minor` keys) to the canonical
  set; regenerate the `contracts/supervisory-entities/golden` fixtures and
  re-vendor miniforge-control.
- Migrate display/consumer sites (`tui-views`, `connector-linter`, gate lint
  default).

## Test plan

- New `schema/interface-test` severity tests: enum is the 5-level scale;
  `Severity` rejects legacy `:major`/`:minor`; `normalize-severity` maps legacy
  to canonical; ordering by rank. 14 tests, 56 assertions.
- Full `schema` suite: 24 tests, 115 assertions, 0 failures (no regressions).
- `bb poly:check` clean.

## Related

- Governance audit Finding 6.
