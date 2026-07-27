<!--
  Title: Fix stratum-lint autofix for components/knowledge (Wave 1)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: stratum-lint autofix for components/knowledge (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/knowledge` (`src` + `test`) to
replace decorative `Layer N` headings with real ones + `^{:stratum n}`
metadata derived from each file's actual same-file reference graph, per
rule 210 (`standards/miniforge/languages/clojure.mdc`). One of the
per-component Wave 1 batches from `work/stratum-lint-baseline-2026-07-24.md`
(batch 5). Not to be confused with `components/knowledge-pack`, a separate
component already fixed in batch 3 (#1494).

## Motivation

`components/knowledge` carried 14 baseline findings — 4 `SL002`, 8 `SL003`,
2 `SL004`, **zero `SL001`** — so no upward-reference/cycle risk needed
triage before running the mechanical fixer; matches the Wave 1 batch
criteria exactly. A plain (non-`--fix`) lint run before touching anything
reproduced the baseline's 14 findings exactly, confirming the count hadn't
drifted since the baseline was taken.

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "bef8657a2efd3b1ba9e1a4f510693c9fbca45abd" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/knowledge
```

All 11 `src` files and all 9 `test` files rewrote (20 files total). No
`SL008` refusal — no reader-conditional-wrapped defn hit that class of
issue in this component.

- `interface.clj` (1 real layer), `messages.clj` (1): all defs collapsed to
  a single real `Layer 0` — `interface.clj` is a pure re-export facade over
  `schema`/`zettel`/`store`/`learning`/`loader`/`trust`/`yaml`, none
  referencing each other in this file. `interface.clj` previously carried
  10 decorative `Layer N` (0-9) headings. Neither ns docstring made a
  "how many layers" claim, so nothing to correct.
- `promotion.clj` (4, over budget — unchanged from baseline), `trust.clj`
  (5, over budget — up from the baseline's mislabeled 4): headings +
  metadata only; neither ns docstring made a layer-count claim to correct.
- `learning.clj` (3, within budget), `policy_lookup.clj` (3, within
  budget): both ns docstrings previously described a different-content,
  differently-numbered breakdown (e.g. `learning.clj` claimed "Layer 0:
  Learning capture / Layer 1: Learning promotion / Layer 2: Pattern
  detection", but the real Layer 0 groups `capture-learning`,
  `promote-learning`, `detect-recurring-patterns`, AND `list-learnings`
  together); corrected to describe the real grouping.
- `schema.clj`: real depth is 3 layers (enums + primitive-only schemas at
  Layer 0, compound schemas referencing those enums at Layer 1, `Zettel`
  itself at Layer 2) — **down** from the baseline's 4-layer `SL003`
  finding, now within budget. Ns docstring corrected to match.
- `store.clj` (4, over budget), `loader.clj` (5, over budget), `zettel.clj`
  (5, over budget): genuinely new information the baseline's plain-lint
  pass couldn't see, since each of these files' pre-fix headings happened
  to already be monotonic and within-budget by coincidence while
  *mislabeling* the real reference depth. `--fix`'s reference-graph
  computation is the only way to discover this; documented as a Wave 2
  namespace-split candidate for each, below. All three ns docstrings
  previously claimed a lower, differently-mapped layer count; corrected by
  hand to describe the real per-layer grouping (see diffs for exact
  wording).
- `yaml.clj`: also over budget (5 layers, newly surfaced — baseline
  reported zero findings for this file), but its ns docstring made no
  layer-count claim to begin with — headings + metadata only.
- `store.clj` also had one decorative `;---- Layer 0.5` banner (a
  non-integer heading stratum-lint's regex doesn't recognize, so it wasn't
  touched by `--fix`) sitting between two defs both later computed as real
  `Layer 0` — a false sub-boundary. Dropped the heading line by hand,
  keeping its descriptive text ("File-backed persistent store") as a plain
  comment. Same class of fix as prior Wave 1 batches (e.g.
  `components/event-stream`).
- Test files: mechanical only. `pattern_detection_test.clj` had two
  `SL004` findings (`fresh-store`, `seed-learnings!` before the first real
  heading, previously under a decorative `;---- Helpers` banner) — both
  now correctly grouped under a real `Layer 0`. `trust_test.clj` had a
  `Layer 2` heading repeated four times (`SL002`) — all four `deftest`
  groups collapsed to a single real `Layer 0`, since none of the tests
  reference each other in-file. `use-fixtures` calls in
  `file_backed_store_test.clj` and `learning_test.clj` got swept to the
  file's appendix (after all `deftest` forms) — `--fix`'s known behavior
  for any non-def top-level form. Verified this is safe: `use-fixtures`
  only needs to execute once during namespace load, which completes in
  full before `clojure.test/run-tests` ever runs a test, so its position
  relative to the `deftest` forms in the file has no effect on behavior.

No change in runtime behavior anywhere in this diff — heading text,
`^{:stratum n}` metadata, def/deftest reordering, and (six files: `zettel`,
`loader`, `schema`, `store`, `learning`, `policy_lookup`) ns docstring
corrections to match the real, now-computed layer structure.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before touching anything —
   reproduced the baseline's exact 14 findings, 0 `SL001`.
2. Ran `--fix`, then a second `--fix` pass immediately after — zero diff,
   confirms idempotency.
3. Read the full diff for all 20 changed files. No same-line trailing
   comment was displaced onto the wrong def. Found one decorative
   non-integer heading (`store.clj`'s `Layer 0.5`, described above) and six
   stale ns-docstring layer-count claims (`zettel.clj`, `loader.clj`,
   `schema.clj`, `store.clj`, `learning.clj`, `policy_lookup.clj`);
   hand-corrected all six.
4. Re-ran `--fix` a third time after the manual edits — zero diff, still
   idempotent.
5. `clj-kondo --lint components/knowledge`: 0 errors, 0 warnings.
6. Ran all 9 test namespaces directly via `clojure -M:dev:test`
   (`file-backed-store-test`, `learning-test`, `loader-test`,
   `pattern-detection-test`, `policy-lookup-test`, `promotion-test`,
   `store-test`, `trust-test`, `zettel-revision-test`): **106 tests, 311
   assertions, 0 failures, 0 errors.**
7. Re-ran plain `stratum-lint` after the fix: `SL001`/`SL002`/`SL004` clear
   everywhere. `SL003` remains on 6 files — `loader.clj` (5 layers),
   `promotion.clj` (4), `store.clj` (4), `trust.clj` (5), `yaml.clj` (5),
   `zettel.clj` (5). `promotion.clj` was already `SL003` in the baseline
   (4 layers, unchanged). The other five are newly surfaced by `--fix`'s
   accurate reference-graph computation — the baseline's plain-lint pass
   under-counted them because their pre-fix headings were coincidentally
   monotonic and in-budget while describing the wrong grouping. All six
   deferred to Wave 2 (real namespace split), consistent with how prior
   Wave 1 batches handled the same situation.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order/docstring-only. Pre-commit's
`lint:stratum` autofixer keeps this component clean going forward; the six
files above stay advisory (`MINIFORGE_STRATUM_BUDGET_MODE=warn` at commit
time) until Wave 2 splits them.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1, batch 5)
- Distinct component from `components/knowledge-pack` (already fixed, #1494)
- Follow-on: Wave 2 namespace split for `components/knowledge/src/ai/miniforge/knowledge/{loader,promotion,store,trust,yaml,zettel}.clj`
  (4-5 real layers each, over the 3-layer budget)

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second (and third, post-manual-edit) `--fix` pass confirms
      idempotency (zero diff)
- [x] Diff read in full for all 20 changed files; mechanical, plus one
      decorative-heading removal and six hand-corrected stale docstring
      claims
- [x] `clj-kondo` clean (0 errors, 0 warnings)
- [x] Component tests pass (106 tests, 311 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero findings except `SL003` on 6 files
      (documented above, tracked as Wave 2)
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
