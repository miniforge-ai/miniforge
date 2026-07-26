# fix: stratum-lint autofix for components/workflow-compliance-scanner (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/workflow-compliance-scanner`
(`src` + `test`) to replace decorative `Layer N` heading reuse with real
headings and `^{:stratum n}` metadata derived from each file's actual
same-file reference graph. Purely mechanical: no logic changes. One of
the smaller per-component Wave 1 PRs from
`work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

Baseline for this component: 4 `SL002` findings, all in
`phases.clj` — the same `Layer 0`/`Layer 1` headings reused as repeated
section banners (`Helpers` / `Default configs` under one `Layer 0`, four
separate interceptor sections each stamped `Layer 1`) instead of one
heading per real stratum. Zero `SL001` findings, so no
upward-reference/cycle risk to reason about before running the mechanical
fixer — matches the Wave 1 batch criteria exactly. `interface.clj` had no
findings and is untouched by the fix.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "14965e1ee1a175bd00f637d9a9d5f7d27e62b73f" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/workflow-compliance-scanner
```

2 files rewritten: `phases.clj` (`src`) and `phases_test.clj` (`test`).

`phases.clj`: the helper fns (`resolve-repo-path`, `resolve-standards-path`,
`resolve-rules`), the four `default-*-config` defs, and the four
`leave-`/`error-` fns all collapse to real `Layer 0` (nothing else in the
file depends on them). The four `enter-` fns move to `Layer 1` (each calls
a `resolve-*` helper and/or a `default-*-config`). The four
`defmethod phase/get-phase-interceptor-method` implementations land
together at `Layer 2` (each references both an `enter-` and a `leave-`/
`error-` fn) — verified they're all consistently at the same stratum, per
this program's known defmethod-placement bug class. The four
`register-phase-defaults!` calls (side-effecting, not `def`/`defn`/
`defmethod`) sort to the end of the file after the last `defmethod`; this
doesn't change behavior since Clojure evaluates top-level forms in file
order regardless, both registries are independent, and every `def` each
form touches is already bound earlier in the file.

`phases_test.clj` had **no** `Layer N` headings at all before this fix —
only named section banners (`Test Fixtures`, `Registry Tests`, etc.) with
no number, so the baseline's plain lint run silently skipped it (a
documented tool limitation, not a pass). `--fix` added real headings
across 4 strata (`Layer 0`–`3`) tracking the actual `deftest`/stub
dependency chain (e.g. `stub-plan` at `Layer 2` because `Layer 3`'s
`:compliance-plan`/`:compliance-execute` tests depend on it).

No line of executable code changed in either file; diffs are heading
text, metadata, and def/deftest reordering only.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before the fix — reproduced the
   baseline's 4 `SL002` findings exactly, confirmed zero `SL001`.
2. Ran `--fix`, then a second `--fix` pass immediately after — zero diff,
   confirms idempotency.
3. Read the full diff for both changed files. No same-line trailing
   comment was displaced onto the wrong def, and no stale decorative
   `;;---- Layer N: ...` banner survived the fix (checked both files for
   the double-semicolon pattern — none found).
4. `clj-kondo --lint components/workflow-compliance-scanner`: 0 errors,
   0 warnings.
5. Ran `ai.miniforge.workflow-compliance-scanner.phases-test` directly via
   `clojure -A:test`: 20 tests, 61 assertions, 0 failures, 0 errors.
6. Re-ran plain `stratum-lint` after the fix: `SL001`/`SL002`/`SL004`
   clear on both files, and `phases.clj` is fully clean. One new finding:
   `SL003` on `phases_test.clj` — 4 real layers against the 3-layer
   budget. This is **newly surfaced**, not a re-labeled pre-existing
   finding: the file had zero `Layer N` headings before this fix (invisible
   to the baseline scan), so the baseline's per-component finding count
   for this component (4, all `SL002`) never included it. Deferred to
   Wave 2 (real namespace split / test file decomposition).

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order-only. Pre-commit's `lint:stratum`
autofixer keeps this component clean going forward; `phases_test.clj`'s
`SL003` stays advisory (`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit
time) until Wave 2 splits it.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 split for
  `components/workflow-compliance-scanner/test/ai/miniforge/workflow_compliance_scanner/phases_test.clj`
  (4 real layers, over budget, newly surfaced by this fix)

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for both changed files; mechanical-only
- [x] `clj-kondo` clean before/after (0 errors, 0 warnings)
- [x] Component tests pass (20 tests, 61 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero findings except the newly-surfaced
      `SL003` on `phases_test.clj` (documented above, tracked as Wave 2)
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
