# fix: stratum-lint autofix for components/control-plane (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/control-plane` (`src` + `test`)
to replace decorative/miscounted `Layer N` headings with real ones derived
from each file's actual same-file reference graph, plus `^{:stratum n}`
metadata on every def. One of the per-component Wave 1 PRs from
`work/stratum-lint-baseline-2026-07-24.md`.

Not purely mechanical: the autofix left behind stale prose in six ns
docstrings and one test file's leftover banner comments that, after the
real headings moved, either made a false claim about which layer held
which concept or (in two spots) directly contradicted the real heading
sitting right above them. Those are hand-fixed here — text-only, no
logic or behavior change, but not something `--fix` itself did. Described
plainly rather than left for review to catch.

## Motivation

Baseline (plain `stratum-lint`, no `--fix`) on this component:

```text
components/control-plane/src/ai/miniforge/control_plane/decision_queue.clj:232:1: SL003 file uses 4 distinct layers (max 3)
components/control-plane/src/ai/miniforge/control_plane/interface.clj:47:1: SL002 Layer 0 heading appears after Layer 0
components/control-plane/src/ai/miniforge/control_plane/interface.clj:190:1: SL003 file uses 5 distinct layers (max 3)
components/control-plane/src/ai/miniforge/control_plane/registry.clj:192:1: SL003 file uses 4 distinct layers (max 3)
```

4 findings, 3 files, zero `SL001` — matches the Wave 1 batch criteria
(no upward-reference/cycle risk to reason about before running the
mechanical fixer).

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "14965e1ee1a175bd00f637d9a9d5f7d27e62b73f" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/control-plane
```

11 files rewritten — `--fix` normalizes every file in the component, not
just the ones with findings:

- `src`: `decision_queue.clj`, `heartbeat.clj`, `interface.clj`,
  `messages.clj`, `orchestrator.clj`, `registry.clj`, `state_machine.clj`
- `test`: `interface_test.clj`, `orchestrator_edge_test.clj`,
  `orchestrator_supplemental_test.clj`, `orchestrator_test.clj`

Notable recomputations:

- `interface.clj` is a flat re-export facade (every def just aliases a
  var from another component's namespace) — all 25 defs collapsed to a
  single real Layer 0. Both prior findings (`SL002`, `SL003`) disappear.
- `registry.clj`'s real structure is 2 layers, not the 4 its old headings
  implied — its `SL003` finding disappears too.
- `heartbeat.clj` and `state_machine.clj` weren't in the baseline findings
  at all (their old headings were monotonic and within the 3-layer
  budget), but the real reference graph puts each at 4 distinct layers —
  `--fix` surfaces a `SL003` that didn't previously show, because the old
  headings under-counted the real depth rather than over-counting it.
- `decision_queue.clj` stays at 4 real layers — same finding as baseline,
  just re-derived from the graph instead of hand-counted.

No multimethods in this component, so the `defmethod`-stratum-placement
bug class from the pinned fix doesn't apply here. No same-line trailing
comment was displaced onto the wrong def (checked every diff by hand).

### Hand-fixed: stale `Layer N` prose the autofix left behind

Six ns docstrings (`decision_queue.clj`, `interface.clj`, `registry.clj`,
`heartbeat.clj`, `orchestrator.clj`, `state_machine.clj`) carried a
`Layer 0: <concept>` / `Layer 1: <concept>` ... breakdown that predates
this fix. Once the real strata were recomputed, none of these lists still
matched: concepts that used to map one-to-one to a layer number now split
across layers, merged into one, or (for `interface.clj`) collapsed
entirely to a single layer. Reworking the numbers to match would just
re-drift on the next real edit, and the same information is already
carried by the inline single-semicolon section comments (`;; Decision
creation`, `;; Agent CRUD`, etc.) that the fix preserves right above each
group of defs — so the `Layer N` breakdown was deleted from each
docstring rather than relabeled.

`orchestrator_test.clj` separately carried five older-style decorative
banners of the form `;; Layer N: <description>` (a different artifact
than the ns docstring lists above — these predate this file ever having
real `Layer N` headings). Two of them were left flatly wrong by the fix:
`;; Layer 1: Discovery pass ...` and `;; Layer 1: Poll pass` now sit
immediately inside the real `Layer 2` block, contradicting it. The other
three (`Layer 2: start! and stop!`, `Layer 2:
submit-decision-from-agent!`, `Layer 2: resolve-and-deliver!`) still
happened to agree with the real heading beside them, but carry the exact
same staleness risk that just broke the first two. Reworded all five to
drop the layer number and keep the description (e.g. `;; Poll pass`).

## Testing Plan

1. Ran plain `stratum-lint` before the fix — reproduced the baseline's 4
   findings across 3 files exactly, confirmed 0 `SL001`.
2. Ran `--fix`, then ran the identical `--fix` command a second time —
   zero further changes (`git diff` before/after the second pass is
   identical).
3. Read the full diff of all 11 changed files. Found and hand-fixed the
   stale `Layer N` prose described above; found no trailing-comment
   reflow onto the wrong def.
4. Re-ran `--fix` a third time after the hand edits — still zero diff,
   confirming the manual changes didn't disturb the fixed structure.
5. `clj-kondo --lint components/control-plane`: 0 errors, 0 warnings,
   both before and after.
6. Ran all 4 test namespaces directly:
   `clojure -M:dev:test -e "(require ...) (clojure.test/run-tests ...)"`
   — 108 tests, 294 assertions, 0 failures, 0 errors.
7. Re-ran plain `stratum-lint` after the fix:

   ```text
   components/control-plane/src/ai/miniforge/control_plane/decision_queue.clj:256:1: SL003 file uses 4 distinct layers (max 3)
   components/control-plane/src/ai/miniforge/control_plane/heartbeat.clj:92:1: SL003 file uses 4 distinct layers (max 3)
   components/control-plane/src/ai/miniforge/control_plane/state_machine.clj:130:1: SL003 file uses 4 distinct layers (max 3)
   ```

   `decision_queue.clj` is the same pre-existing finding as baseline
   (unchanged layer count, just re-derived). `heartbeat.clj` and
   `state_machine.clj` are newly surfaced by the fix — their old headings
   undercounted the real depth. All three are real over-budget files,
   Wave 2 scope (namespace split), not fixed here.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — headings, `^{:stratum n}` metadata, def/deftest order, and the
hand-fixed docstring/comment prose only. Pre-commit's `lint:stratum`
autofixer keeps this component clean going forward; the three `SL003`
findings stay advisory (`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit
time) until Wave 2 splits those namespaces.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace splits for `decision_queue.clj`,
  `heartbeat.clj`, and `state_machine.clj` (each 4 real layers, over the
  3-layer budget)

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 11 changed files
- [x] Stale `Layer N` docstring/comment prose left by the fix hand-corrected
      (6 ns docstrings + 5 banner comments in `orchestrator_test.clj`)
- [x] Third `--fix` pass after hand edits still zero diff
- [x] `clj-kondo` clean before/after (0 errors, 0 warnings)
- [x] Component tests pass (108 tests, 294 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: 3 pre-existing/newly-surfaced `SL003`
      findings documented above, tracked as Wave 2
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
