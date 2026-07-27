<!--
  Title: fix: stratum-lint autofix for components/workflow-resume (Wave 1)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: stratum-lint autofix for components/workflow-resume (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/workflow-resume` (`src` + `test`)
to replace non-monotonic `Layer N` headings with real `Layer N` headings and
`^{:stratum n}` metadata derived from each file's actual same-file reference
graph. Mechanical: no logic changes. One of the Wave 1 batch 5 per-component
PRs from `work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

Plain (non-`--fix`) `stratum-lint` on `components/workflow-resume` reported:

```text
core.clj:369:1: SL002 Layer 1 heading appears after Layer 1; headings must strictly increase
core.clj:390:1: SL002 Layer 1 heading appears after Layer 1; headings must strictly increase
schema.clj:68:1: SL002 Layer 1 heading appears after Layer 1; headings must strictly increase
core_test.clj:221:1: SL002 Layer 1 heading appears after Layer 1; headings must strictly increase
core_test.clj:533:1: SL003 file uses 5 distinct layers (max 3); split the namespace or extract a component
```

Zero `SL001`, confirming this component carries no upward-reference/cycle
risk requiring human triage before running the mechanical fixer.
`interface.clj` reported no findings at all pre-fix, but was still cargo-cult:
its two headings (`Layer 0`, `Layer 1`) were monotonic so the checker passed
it, yet every def under `Layer 1` ("High-level APIs") turned out to have zero
same-file references — a decorative split, not a real one.

## Changes in Detail

Ran, over the whole component (pin from `tasks/stratum.clj`):

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "bef8657a2efd3b1ba9e1a4f510693c9fbca45abd" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/workflow-resume
```

All 4 `.clj` files in the component were rewritten (3 `src`, 1 `test`).
Diffs are heading text, `^{:stratum n}` metadata, and def/deftest reordering
only — no executable line changed:

- `schema.clj`: `valid-event?` and `validate` were both mislabeled under
  `Layer 1`/no metadata; `--fix` correctly placed both at real `Layer 0`
  (neither has a same-file reference to anything past `EventBase`), leaving
  a single real heading in the file.
- `interface.clj`: the "High-level APIs" comment banner and its three
  re-exports (`reconstruct-context`, `trim-pipeline`,
  `resolve-workflow-identity`) sat under a `Layer 1` heading despite being
  pure `core/...` var re-exports with zero same-file references — real
  stratum for all of them is `Layer 0`. The file collapsed to one real
  layer; the "High-level APIs" comment was kept as plain text since it no
  longer contradicts any heading.
- `core.clj`: the deepest file in the component. `reconstruct-context`'s
  real call chain (`restored-completed-phases` → `completed-checkpoint-phases`
  / `completed-event-phases` → `completed-phase-result?` → `review-blocked?`
  → `blocking-review-decisions`/`completed-phase-statuses` → `resume-config`
  → `read-resume-config`) recomputed to **10** real layers (0–9), previously
  hidden under 2 non-monotonic `Layer 1` banners.
- `core_test.clj`: deftest groups were scattered under repeated `Layer 1`/
  `Layer 2`/`Layer 3` banners with no relationship to which helpers each test
  actually exercised. Recomputed to 3 real layers (0–2) — the file's tests
  mostly call the public API directly (`Layer 0`); `reconstruct-context-integration-test`
  and the two checkpoint-manifest tests that depend on the private
  `configured-checkpoint-data` helper landed at `Layer 1`/`Layer 2`.

No same-line trailing comment was displaced onto the wrong def — grepped the
diff for the known `foo])  ; comment` migration pattern; no matches. No
decorative double-semicolon `;;---- Layer N: <label>` banners were left
behind either — grepped all four files post-fix for `Layer`; every remaining
occurrence is a real, single-semicolon, tool-generated heading.

## Testing Plan

1. Ran plain `stratum-lint` before the fix — reproduced the five findings
   above exactly, zero `SL001`.
2. Ran `--fix`, then a second `--fix` pass immediately after — zero diff
   (empty output, exit 0), confirms idempotency.
3. Read the full diff for all 4 changed files. Confirmed only heading text,
   `^{:stratum n}` metadata, and def/deftest reordering changed; no
   executable line touched.
4. `clj-kondo --lint components/workflow-resume`: 0 errors, 0 warnings.
   Confirmed identical result (`git stash` + re-lint) on the pre-fix tree —
   not a pre-existing warning being masked.
5. Ran the component's only test namespace directly (`clojure -M:dev:test -e
   "(require 'ai.miniforge.workflow-resume.core-test) (clojure.test/run-tests
   'ai.miniforge.workflow-resume.core-test)"`): 30 tests, 97 assertions, 0
   failures, 0 errors.
6. Re-ran plain `stratum-lint` after the fix. `SL001`/`SL002`/`SL004` clear.
   `SL003` remains on one file, with the real layer count now precisely
   measured from the true reference graph:
   - `core.clj`: **new** finding — 0 pre-fix (the old headings only ever
     used the values 0 and 1, so the checker never saw the file as
     over-budget); true depth is **10** real layers, surfaced only once
     `--fix` recomputed from the actual reference graph.
   - `core_test.clj`: 5 → **3** real layers (old headings had two extra,
     unneeded splits). Finding resolved — now within budget.

   The `core.clj` `SL003` finding is a real over-budget file (Wave 2 scope:
   namespace split), not addressed here. Committed with
   `MINIFORGE_STRATUM_BUDGET_MODE=warn` alongside
   `MINIFORGE_COMMIT_BUDGET_OVERRIDE=1` per the Wave 1 convention for
   pre-existing over-budget files.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order-only. Pre-commit's `lint:stratum` autofixer
keeps this component clean going forward; `core.clj` stays advisory
(`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit time) until Wave 2 splits it.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace split for `core.clj` (10 real layers, over the
  3-layer budget) — likely split along the `reconstruct-context` restoration
  chain (checkpoint/event merging) vs. `resolve-workflow-identity` vs. the
  phase-completion predicate chain (`review-blocked?` /
  `completed-phase-result?` / `completed-checkpoint-phases` /
  `completed-event-phases` / `restored-completed-phases`).

## Checklist

- [x] Confirmed zero `SL001` findings before running `--fix`
- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 4 changed files; mechanical-only
- [x] No decorative `;;---- Layer N: <label>` banners left behind
- [x] `clj-kondo` clean (0 errors, 0 warnings), confirmed not pre-existing
- [x] Component's test namespace passes (30 tests, 97 assertions, 0
      failures/errors)
- [x] Plain lint re-run post-fix: `SL003` remains on `core.clj` (10 real
      layers), documented above, tracked as Wave 2
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
