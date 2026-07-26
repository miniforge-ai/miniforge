<!--
  Title: fix: stratum-lint autofix for components/loop (Wave 1)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: stratum-lint autofix for components/loop (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/loop` (`src` + `test`) to
replace decorative `Layer N` headings with real ones derived from each
file's actual same-file reference graph, and tag every top-level `def`/
`defn`/`deftest` with `^{:stratum n}`. One of the Wave 1 batches from
`work/stratum-lint-baseline-2026-07-24.md` (batch 6, the largest
component in this batch at 40 baseline findings). Mostly mechanical, but
two classes of manual fix were needed beyond the autofix output: one
stale decorative heading that now contradicted its surrounding real
headings in `outer.clj`, and six namespace docstrings that hardcoded a
layer count/description the fix made wrong. No executable logic changed
anywhere.

## Motivation

Baseline findings for this component, confirmed via a fresh plain-lint
run before touching anything (zero `SL001` — no upward-reference/cycle
risk, matching the Wave 1 batch criteria):

- `gates.clj`: 5 `SL004` (defs before the first `Layer` heading) + 1
  `SL002` (heading reuse).
- `repair.clj`: 3 `SL004` + non-monotonic headings.
- `inner.clj`: 2 `SL002` (heading reuse).
- `interface.clj`: 4 `SL002`.
- `schema.clj`: 5 `SL002`.
- Six `test/*.clj` files: 10 `SL002` + 1 `SL003` (`gates_test.clj`, 4
  distinct decorative layers against the 3-layer budget).

40 findings total (31 `SL002`, 8 `SL004`, 1 `SL003`, 0 `SL001`) —
matches `work/stratum-lint-baseline-2026-07-24.md`'s per-component table
exactly.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "bef8657a2efd3b1ba9e1a4f510693c9fbca45abd" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/loop
```

20 of the component's files were rewritten (11 `src`, 9 `test`) —
`--fix` normalizes every file it touches, not just the ones with
findings. Diffs are heading text, `^{:stratum n}` metadata, and
def/deftest reordering only; verified with a form-level equality check
(read every top-level form from the pre-fix and post-fix version of
each file via the Clojure reader and diffed the resulting multisets,
which are insensitive to reordering and to metadata since Clojure's `=`
ignores both) — every file matched exactly except three where the
tool's regex-literal forms (`#"..."`) aren't `.equals()`-comparable in
Java, a false-positive artifact of the verification method itself, not
a real difference; confirmed those three by direct diff read instead.

Notable autofix outcomes worth calling out because they look alarming
in a raw diff:

- `interface.clj`'s every def collapsed to a **single** real layer
  (Layer 0). This file is a thin facade over `inner`/`outer`/`gates`/
  `repair`/`escalation`/`schema` — every def just delegates to another
  namespace, so there are no same-file references between its own defs
  at all; the old headings implying 2 distinct layers were entirely
  decorative.
- `gates_test.clj`'s pre-fix `SL003` (4 decorative layers) collapsed to
  2 real layers — the fixtures/constructor tests all sit at Layer 0,
  and every other `deftest` (including the previously-stranded
  `gate-repair-protocol-test` under an old "Layer 3") sits at Layer 1,
  since none of the test groups actually depend on each other.
- `inner.clj` and `outer.clj` each expanded from 3 decorative layers to
  10 and 9 real layers respectively — the true FSM/step-function/runner
  call chain is much deeper than the old banners implied. Both are
  genuine `SL003` over-budget findings, not something this mechanical
  pass can fix (see Testing Plan and Deployment Plan).

One thing the autofix did **not** resolve, found during the mandated
full-diff read: `outer.clj` had a stale decorative `;---- Layer 1.5`
sub-banner (labeled "Result helpers") sitting directly below the real,
generated `Layer 0` heading, with `phase-succeeded?` and `log-phase`
(both correctly tagged `^{:stratum 0}` by the fix) underneath it. The
non-integer "1.5" isn't recognized by stratum-lint's heading regex, so
it survived from the old `Layer 0 / Layer 1 / Layer 2` decorative
scheme untouched — reading "1.5" directly under a real "Layer 0" heading
and above the real "Layer 1" heading, contradicting both. Dropped the
`Layer 1.5` heading by hand, keeping "Result helpers" as a plain
comment (same fix class as this program's prior batches, e.g.
`components/knowledge`'s `store.clj` "Layer 0.5").

Also updated by hand, six namespace docstrings whose hardcoded layer
summary the fix invalidated — `escalation.clj` (3→4 real layers),
`gates.clj` (3→4), `repair.clj` (3→4), `schema.clj` (2→5), `outer.clj`
(3→9), and `inner.clj` (3→10). Rewrote each to name the actual
functions/defs at every real layer and why (which same-file def each
one calls), rather than the old thematic-but-untrue one-line-per-layer
summaries. `inner.clj` and `outer.clj`'s docstrings additionally flag
that their real layer count is over budget and point at
`work/stratum-lint-baseline-2026-07-24.md` for the Wave 2 follow-on.

No `#?(...)` reader-conditional-wrapped defs in this component, so the
SL008 fix in the current pin never came into play, and no same-line
trailing-comment displacement was found in any file.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before any change — reproduced
   the 40 findings above exactly (31 `SL002`, 8 `SL004`, 1 `SL003`, 0
   `SL001`), confirmed 0 `SL001` before proceeding.
2. Ran `--fix` over the whole component — 20 files rewritten.
3. Ran `--fix` a second time immediately after — zero diff (confirmed
   both by the tool's own silent/no-rewrite output and by an md5 hash
   comparison of every changed file before/after), confirms idempotency.
4. Read the full diff for all 20 changed files, plus a form-level
   equality check (Clojure reader, frequencies of top-level forms) as
   an independent cross-check that no logic changed. Found and
   hand-fixed the contradictory `Layer 1.5` banner in `outer.clj`;
   updated 6 stale namespace docstrings (above).
5. Ran `--fix` again after the hand edits — zero diff, confirms the
   manual fixes are stable under the tool.
6. `clj-kondo --lint components/loop`: 0 errors, 0 warnings — both
   before and after.
7. Ran all 9 test namespaces directly via `clojure -M:dev:test`
   (`escalation-test`, `gate-anomaly-shape-test`, `gates-test`,
   `inner-test`, `interface-test`, `messages-test`,
   `metrics-accumulation-test`, `outer-test`, `repair-messages-test`):
   69 tests, 324 assertions, 0 failures, 0 errors.
8. Re-ran plain `stratum-lint` after the fix: `SL001`/`SL002`/`SL004`
   clear across the component. `SL003` remains, newly surfaced (higher
   than the pre-fix decorative count, not a regression) on six files:
   - `escalation.clj`: 4 real layers.
   - `gates.clj`: 4 real layers.
   - `repair.clj`: 4 real layers.
   - `schema.clj`: 5 real layers.
   - `outer.clj`: 9 real layers.
   - `inner.clj`: 10 real layers.

   All six are genuinely over the 3-layer budget the old decorative
   headings hid by undercounting, not a regression this PR introduces —
   deferred to Wave 2 (real namespace split), consistent with how prior
   Wave 1 PRs (e.g. `schema`, `repo-dag`) handled the same situation.
   `gates_test.clj`'s original `SL003` is fully resolved (collapsed to 2
   real layers, well under budget).

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order/docstring-only. Pre-commit's
`lint:stratum` autofixer keeps this component clean going forward; the
six files above keep their `SL003` advisory
(`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit time) until Wave 2
splits them — `inner.clj` (10 layers) and `outer.clj` (9 layers) are the
deepest single-file call chains found anywhere in this program so far
and are the priority candidates for that split.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1, batch 6)
- Precedent for the decorative-banner hand-fix: prior Wave 1 batches,
  e.g. `components/knowledge`'s `store.clj` "Layer 0.5" banner
- Follow-on: Wave 2 namespace split for
  `components/loop/src/ai/miniforge/loop/inner.clj` (10 real layers)
  and `components/loop/src/ai/miniforge/loop/outer.clj` (9 real
  layers), plus smaller splits for `escalation.clj`, `gates.clj`,
  `repair.clj`, and `schema.clj` (4-5 real layers each)

## Checklist

- [x] Plain lint confirmed zero `SL001` before touching anything
- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff, verified via
      md5 hash comparison)
- [x] Diff read in full for all 20 changed files, cross-checked with a
      form-level equality comparison via the Clojure reader
- [x] Contradictory decorative `Layer 1.5` banner (`outer.clj`) found
      and removed by hand; keeping "Result helpers" as a plain comment
- [x] Six namespace docstrings (`escalation.clj`, `gates.clj`,
      `repair.clj`, `schema.clj`, `outer.clj`, `inner.clj`) updated to
      match the real post-fix layer structure
- [x] Further `--fix` pass after hand edits confirms stability (zero
      diff)
- [x] `clj-kondo` clean (0 errors, 0 warnings before/after)
- [x] Component tests pass (69 tests, 324 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero `SL001`/`SL002`/`SL004`; `SL003`
      remains on 6 files — newly surfaced by the fix (not pre-existing),
      tracked as Wave 2 above
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
