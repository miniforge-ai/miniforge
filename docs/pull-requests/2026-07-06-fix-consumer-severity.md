<!--
  Title: Migrate severity consumer sites to the canonical enum
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: migrate severity consumer sites to the canonical enum

Branch: `fix/consumer-severity`

## Summary

Step 3 of governance-audit Finding 6. Migrates the in-repo severity **consumers**
off `:major`/`:minor` onto the canonical `:critical/:high/:medium/:low/:info`
(established in #1382, made authoritative for packs in #1383). `:major` → `:high`,
`:minor` → `:low`.

One of these was silently broken by #1383: `tui-views` renders the policy-pack
`:evaluation/summary` histogram, whose keys became `:high`/`:medium`/`:low` — but
`tui-views` still read `:major`/`:minor`, so those counts stopped displaying.

## Changes

- `tui-views/.../trees.clj`: `severity-prefix` / `severity-color` labels and
  `severity-summary-nodes` now use the 5-level keys (adds `:medium`).
- `connector-linter/etl.clj`: the `map-severity` default is `:low` (was
  `:minor`); `resources/.../linter-mappings.edn` maps sources to canonical
  values.
- `gate/pre_verify_lint.clj`: lint-error severity default `:high` (was `:major`).
- Test fixtures across `gate`, `connector-linter`, `pr-scoring`,
  `semantic-analyzer`, `tui-views` migrated so the codebase carries one severity
  vocabulary.

## Remaining (not this PR)

- `supervisory-state` / `evidence-bundle` `violation-severities` copies + the
  `:policy/summary` histogram + golden fixtures + miniforge-control re-vendor
  (step 4, cross-repo).
- One line of embedded doc text in `miniforge-standards.pack.edn` ("compiles at
  `:major` severity") originates from `meta/rule-format.mdc` in the standards
  submodule — a standards-repo edit, not this repo.

## Test plan

- Migrated components: `tui-views`, `connector-linter`, `gate`, `pr-scoring`,
  `semantic-analyzer` — all green (170 tests across the runs).
- `bb pre-commit` green; `bb poly:check` clean.

## Related

- Governance audit Finding 6. Depends on #1382, #1383.
