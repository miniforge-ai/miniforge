<!--
  Title: Split policy-pack/mapping.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split mapping.clj (rule 210)

## Overview

Splits the mapping-artifact schema hierarchy out of
`ai.miniforge.policy-pack.mapping` into a new sibling namespace,
`ai.miniforge.policy-pack.mapping.schema`, resolving a stratum-lint
SL003 finding (the combined namespace measured 5 real layers, over
the rule 210 budget of 3).

## Motivation

Part of the stratum-lint rule-210 remediation program's Wave 2
continuation (policy-pack batch 2). `mapping.clj` (219 lines) has real
external callers (confirmed via a fully-qualified-namespace grep, not
a zero-fan-in file), so the split had to keep every existing call site
working.

## Changes in Detail

- New file `mapping/schema.clj` (`ai.miniforge.policy-pack.mapping.schema`):
  `MappingType`, `MappingConfidence`, `MappingSystemRef` (Layer 0),
  `MappingAuthorship`, `MappingEntry` (Layer 1, over `MappingConfidence`/
  `MappingType`), `MappingArtifact` (Layer 2, over `MappingSystemRef` +
  `MappingEntry` + `MappingAuthorship`) — 3 layers, at the budget.
- `mapping.clj`: `resolve-mapping`, `valid-mapping?`, `validate-mapping`
  (Layer 0 — the two validation fns now reference `schema/MappingArtifact`,
  qualified/cross-namespace, so they no longer count as depending on a
  same-file def), `project-report` (Layer 1, over `resolve-mapping`),
  `load-mapping` (Layer 1, over `valid-mapping?` + `validate-mapping`) —
  2 layers (down from 5).
- `interface/mapping.clj` (the Polylith component interface): now
  requires both `ai.miniforge.policy-pack.mapping` (functions) and
  `ai.miniforge.policy-pack.mapping.schema` (schemas) instead of pulling
  everything through the single `mapping` alias; its four schema re-export
  defs (`MappingArtifact`, `MappingEntry`, `MappingAuthorship`,
  `MappingType`) now point at `schema/*`. The interface's own public API
  (the def names it exports) is unchanged.
- `mapping_test.clj`: no changes needed — it exercises the public
  functions (`sut/valid-mapping?`, `sut/resolve-mapping`,
  `sut/project-report`) with inline data literals, never referencing the
  schema defs by symbol.
- No project-level (`projects/miniforge/`) caller found for this
  namespace, unlike the `knowledge_safety.clj` split in this same batch.

This is pure code motion — no def was added, removed, or renamed; the
only fan-out is the two-namespace require `interface/mapping.clj` now
needs, one per file the schema symbols and the functions each moved to.

## Testing Plan

- `stratum-lint --fix` then plain `stratum-lint`, both exit 0 on all
  three touched/new files (was SL003 exit 1 on the original
  `mapping.clj`).
- `clj-kondo` (`bb lint:clj`) on the three files: 0 errors, 0 warnings.
- `clojure -M:poly check`: OK (Polylith workspace structure intact).
- Direct `require` of all three touched namespaces
  (`ai.miniforge.policy-pack.mapping.schema`,
  `ai.miniforge.policy-pack.mapping`,
  `ai.miniforge.policy-pack.interface.mapping`) with local component
  deps on the classpath: loads clean, no compile errors.
- Repo-wide grep for the fully-qualified namespace
  `ai\.miniforge\.policy-pack\.mapping\b` across `components`, `bases`,
  and `projects` (not a symbol-prefix guess — the methodology that
  missed an aliased call site earlier in this batch) found exactly
  three files: `mapping.clj` itself, `interface/mapping.clj`, and
  `mapping_test.clj` (test needed no changes, explained above). No
  `projects/` caller exists for this file.
- `ai.miniforge.policy-pack.mapping-test` run directly
  (`clojure -M:test`, component dir): 5 tests, 29 assertions, 0
  failures, 0 errors.
- Full `components/policy-pack` test suite run directly (all 30 test
  namespaces, `clojure -M:test`): 294 tests, 2686 assertions, 1
  failure, 0 errors. The one failure
  (`standard-packs-test/compiled-standards-pack-is-valid-edn-test`) is
  a pre-existing cwd artifact of this ad hoc invocation — the test
  reads `components/phase/resources/packs/miniforge-standards.pack.edn`
  relative to the process's working directory and says so in its own
  assertion message ("run from repo root"); it was invoked from
  `components/policy-pack/`, not the repo root. Confirmed the file
  exists at that path relative to the repo root and is unrelated to
  this change — nothing in this diff touches `standard_packs_test.clj`
  or the phase component.
- `bb test` (the repo's stable-derived change-scope runner): the
  `changed-projects-since-stable` diff against the most recent
  `stable-*`/`stable/*` tag is large right now (the repo's stable-tag
  anchor has drifted from current `main`, unrelated to this PR), so
  the run took roughly 20+ minutes working through many unrelated
  bricks (`connector-sarif`, `content-hash`, `cursor-store`,
  `dag-executor`, `llm`, `logging`, `loop`, `messages`, etc.) before
  reaching completion. Ran to completion: exit code 0, zero failures
  and zero errors in every namespace it tested.
- `git commit` itself ran the full `bb pre-commit` hook (commit-budget,
  `poly check`, `clj-kondo`, stratum-lint, markdown format, pre-commit
  smoke tests, GraalVM/Babashka compatibility): all green — smoke
  tests 345 tests/1301 assertions/0 failures, GraalVM compat 8
  tests/623 assertions/0 failures.

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file.
Remaining `policy-pack` files over budget are tracked separately in
the rule-210 remediation program.

## Related Issues/PRs

- Part of the stratum-lint rule-210 Wave 2 continuation (see
  `workflow_runner.clj` splits miniforge#1662-#1667, the
  `compliance-scanner` split #1580, and the `knowledge_safety.clj`
  split #1731 for the established convention this follows).

## Checklist

- [x] stratum-lint clean on all resulting files
- [x] `clj-kondo` clean, `poly check` OK
- [x] `components/policy-pack` full test suite green (294/295 real
      assertions; the 1 non-pass is an explained cwd artifact, not a
      regression — see Testing Plan)
- [x] `bb test` (since-stable scope) green: exit 0, zero failures,
      zero errors (~20 min run — see Testing Plan)
- [x] Adversarial self-review: def set unchanged (relocated only)
- [x] Zero fan-in check done via fully-qualified namespace grep across
      components, bases, AND projects (not a symbol guess)
- [x] `commit-budget` (136/200) within limits; full `bb pre-commit`
      hook green (smoke tests + GraalVM compat, see Testing Plan)
