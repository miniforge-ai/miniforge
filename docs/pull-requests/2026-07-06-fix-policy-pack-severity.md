<!--
  Title: Migrate policy-pack to the canonical severity enum
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: migrate policy-pack to the canonical severity enum

Branch: `fix/policy-pack-severity`

## Summary

Step 2 of governance-audit Finding 6. Migrates the `policy-pack` component off
its own `:critical/:major/:minor/:info` vocabulary onto the shared canonical
`schema/severities` (`:critical/:high/:medium/:low/:info`, established in #1382).
`:major` → `:high`, `:minor` → `:low`.

After this, `RuleSeverity` is `schema/Severity`, so a rule whose severity is
outside the canonical enum is rejected — the "schema check rejects an
out-of-enum severity" the finding asks for. Severity remains advisory (it does
not gate; PR1 made enforcement action authoritative).

## Changes

Source (`policy-pack`):

- `deps.edn`: add `ai.miniforge/schema`.
- `schema.clj`: `rule-severities` and `RuleSeverity` now alias
  `schema/severities` / `schema/Severity`.
- `core.clj`: delete the duplicated `severity-order` /`compare-severity` /
  `more-severe`; `compare-severity` / `more-severe` alias the shared helpers.
- `mdc_compiler.clj`: emit `:high` (blocking / always-apply) / `:low` (else)
  instead of `:major` / `:minor`.
- `knowledge_safety.clj`, `loader.clj`, `registry.clj`, `external.clj` (the
  `:evaluation/summary` histogram now keys `:critical/:high/:medium/:low/:info`),
  and interface docstrings.
- Regenerated the compiled `miniforge-standards.pack.edn` (27 `:high`, 28 `:low`)
  and the 11 shipped hand-authored packs (`resources/policy_pack/packs/*`).

Tests: the 12 policy-pack test files migrate their fixtures; the schema tests now
pin that legacy `:major`/`:minor` are **rejected** and the enum is the 5-level
scale.

Split into two commits (source, then tests) to keep each under the 200-line
commit budget; the strict enum makes the migration atomic, so the working tree
is consistent at each commit.

## Follow-ups

- Consumers still using `:major`/`:minor`: `tui-views`, `connector-linter`, gate
  `pre-verify-lint` default (step 3).
- `supervisory-state` / `evidence-bundle` `violation-severities` copies + the
  `:policy/summary` histogram + golden fixtures + miniforge-control re-vendor
  (step 4).

## Test plan

- `policy-pack` suite: 234 tests, 2398 assertions, 0 failures.
- `gate` suite (consumes policy-pack): 55 tests, 140 assertions, 0 failures.
- `bb poly:check` clean (schema dep introduces no cycle).

## Related

- Governance audit Finding 6. Depends on #1382.
