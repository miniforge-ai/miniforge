<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: stratum-lint autofix for components/orchestrator (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/orchestrator` (`src` + `test`)
to replace decorative `Layer N` banners with real `Layer N` headings and
`^{:stratum n}` metadata derived from each file's actual same-file
reference graph. Mechanical: no logic changes, no execution-order changes.
One of the Wave 1 batch 5 per-component PRs from
`work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

Plain (non-`--fix`) `stratum-lint` on `components/orchestrator` reported
zero `SL001` (upward-reference/cycle risk) — confirmed before touching
anything, since this component wasn't among the six already spot-checked
in the baseline triage. Findings were `SL003` (over the 3-layer budget)
and `SL002` (non-monotonic heading reuse):

```text
core.clj:198:1: SL003 file uses 5 distinct layers (max 3)
interface.clj:216:1: SL003 file uses 6 distinct layers (max 3)
protocol.clj:80:1: SL003 file uses 4 distinct layers (max 3)
interface_test.clj:89,104,136,184,200: SL002 Layer 1 heading repeated non-monotonically (x5)
```

`messages.clj` reported nothing at all pre-fix, but carried no `Layer N`
heading anywhere — the documented "no heading = silently skipped"
limitation, not a clean bill of health.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "bef8657a2efd3b1ba9e1a4f510693c9fbca45abd" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/orchestrator
```

All 5 `.clj` files were rewritten (4 `src`, 1 `test`). Diffs are heading
text, `^{:stratum n}` metadata, and def/deftest reordering only — no
executable line changed. Notable findings from the recomputed real
dependency graph:

- `protocol.clj` declares four independent protocols
  (`Orchestrator`, `TaskRouter`, `BudgetManager`, `KnowledgeCoordinator`)
  with no same-file reference between them. The old headings numbered
  them 0-3 sequentially by writing order; real stratum for all four is 0.
  The pre-fix `SL003` (4 layers) was an artifact of that sequential
  numbering, not real complexity — fully resolved, not deferred.
- `interface.clj` is a pure re-export/delegation layer: every `def`/`defn`
  calls straight into `core/*` or `proto/*` (required namespaces, not
  same-file defs). Real stratum for every def is 0. The pre-fix `SL003`
  (6 layers) was the same sequential-numbering artifact — fully resolved.
- `core.clj` has genuine same-file structure. `--fix` moved the
  `ControlPlane` defrecord (previously written last, under a "Layer 4"
  banner) up to real Layer 0: its protocol method bodies call `wf/`,
  `proto/`, and `log/` functions from required namespaces only, no
  same-file def. Real Layer 0 also holds `format-zettel-for-context`,
  `build-repair-learning`, `default-config`, and `task-type->agent-role`.
  `SimpleTaskRouter`/`SimpleBudgetManager`/`format-knowledge-block` sit at
  real Layer 1 (reference Layer 0 defs); `create-router`/
  `create-budget-manager`/`SimpleKnowledgeCoordinator` at Layer 2;
  `create-knowledge-coordinator` at Layer 3; `create-control-plane`
  (constructs a `ControlPlane` via `->ControlPlane`, so must follow its
  Layer-0 defrecord in file order) at Layer 4; the `create-orchestrator`
  backward-compat alias at Layer 5. Six real layers, confirmed against
  the compile-order constraint (defrecord must precede any
  `->RecordName` call) by reading the full rewritten file top to bottom.
- `interface_test.clj`: the five test fixture helpers (`task`,
  `repair-entry`, `zettel`, `learning-with-confidence`,
  `capture-knowledge-store`) and every `deftest` that calls none of them
  landed at real Layer 0; every `deftest` that calls one of those
  fixtures landed at real Layer 1. The old headings repeated "Layer 1"
  five times non-monotonically (the `SL002` findings) instead of
  separating these two real strata.
- `messages.clj` gained its first real heading (`Layer 0` on the sole
  `def t`) plus a stray blank line removed between the license header and
  the `ns` form (`--fix` normalizes this on every file it touches,
  matching prior batches).

No same-line trailing comment was displaced onto the wrong def and no
stale decorative double-semicolon banner was left contradicting a real
heading — checked by reading the full diff and the full resulting file
for all 5 changed files, not just the diff hunks.

## Testing Plan

1. Ran plain `stratum-lint` before the fix — reproduced the findings
   above exactly. Confirmed zero `SL001` before proceeding, per this
   component's stated risk (not pre-vetted as SL001-free).
2. Ran `--fix`, then a second `--fix` pass immediately after — no output,
   zero diff, confirms idempotency.
3. Read the full diff and the full resulting content for all 5 changed
   files. Confirmed only heading text, `^{:stratum n}` metadata, and
   def/deftest reordering changed; independently re-derived the expected
   real stratum for every def from its same-file reference graph and
   matched it against what `--fix` produced.
4. `clj-kondo --lint components/orchestrator`: 0 errors, 1 warning
   (`Unresolved namespace ai.miniforge.knowledge.interface` in a
   `with-redefs` form in `interface_test.clj`). Confirmed pre-existing via
   `git stash` + re-lint — same warning at the pre-fix line number,
   unrelated to this diff.
5. Ran the component's sole test namespace directly:
   `clojure -M:dev:test -e "(require 'ai.miniforge.orchestrator.interface-test)
   (require 'clojure.test) (clojure.test/run-tests
   'ai.miniforge.orchestrator.interface-test)"` — 21 tests, 79 assertions,
   0 failures, 0 errors.
6. Re-ran plain `stratum-lint` after the fix:
   - `protocol.clj`: **resolved**. Old headings claimed 4 layers; true
     depth is 1 (real Layer 0 only). No longer reported.
   - `interface.clj`: **resolved**. Old headings claimed 6 layers; true
     depth is 1 (real Layer 0 only). No longer reported.
   - `core.clj`: still `SL003`, now precisely measured — 5 → **6** real
     layers (old decorative count had merged two distinct strata under
     one heading). Genuine over-budget file, Wave 2 scope (namespace
     split), not addressed here.
   - `interface_test.clj`: `SL002` resolved — real 2-layer structure, well
     under budget.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order-only. Pre-commit's `lint:stratum`
autofixer keeps this component clean going forward; `core.clj` stays
advisory (`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit time) until
Wave 2 splits it.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace split for `core.clj` (6 real layers, over
  the 3-layer budget).

## Checklist

- [x] Plain lint run first; confirmed zero `SL001` before proceeding
- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff and full file content read for all 5 changed files;
      mechanical-only, compile order independently re-derived and checked
- [x] `clj-kondo` clean (0 errors; 1 pre-existing warning confirmed via
      `git stash`)
- [x] Component test namespace passes (21 tests, 79 assertions, 0
      failures/errors)
- [x] Plain lint re-run post-fix: `protocol.clj`/`interface.clj` `SL003`
      resolved (were decorative-numbering artifacts, not real
      complexity); `core.clj` `SL003` remains, documented above with
      precise before/after counts, tracked as Wave 2
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
