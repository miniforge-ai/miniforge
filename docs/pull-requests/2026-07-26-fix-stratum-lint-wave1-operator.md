<!--
  Title: Fix stratum-lint autofix for components/operator (Wave 1)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: stratum-lint autofix for components/operator (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/operator` (`src` + `test`) to
replace decorative `Layer N` banners with real `Layer N` headings and
`^{:stratum n}` metadata derived from each file's actual same-file
reference graph. Mechanical: no logic changes, no execution-order changes.
Wave 1 batch 6 of the program tracked in
`work/stratum-lint-baseline-2026-07-24.md`.

## Motivation

Plain (non-`--fix`) `stratum-lint` on `components/operator` reported zero
`SL001` (upward-reference/cycle risk) — confirmed before touching
anything, since this component was not among the six already spot-checked
in the baseline triage. Findings were all `SL003` (over the 3-layer
budget) and `SL002` (non-monotonic heading reuse):

```text
application.clj:401:1: SL003 file uses 7 distinct layers (max 3)
core.clj:242:1: SL003 file uses 6 distinct layers (max 3)
llm_improvement_generator.clj:162:1: SL003 file uses 4 distinct layers (max 3)
llm_pattern_detector.clj:156:1: SL003 file uses 4 distinct layers (max 3)
mechanism.clj:303:1: SL003 file uses 4 distinct layers (max 3)
protocol.clj:116:1: SL003 file uses 5 distinct layers (max 3)
core_test.clj: SL002 Layer 1/2/3 headings repeated non-monotonically (x6)
core_test.clj:262:1: SL003 file uses 5 distinct layers (max 3)
defaults_test.clj:45:1: SL002 Layer 1 heading repeated
protocol_test.clj:39:1: SL002 Layer 1 heading repeated
```

## Changes in Detail

Ran, over the whole component:

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "bef8657a2efd3b1ba9e1a4f510693c9fbca45abd" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/operator
```

18 of the component's 20 `.clj` files were rewritten (9 `src`, 9 `test`).
`application.clj` and `mechanism.clj` were left untouched: their existing
`Layer 0..N` headings were already strictly monotonic and matched the
real reference graph exactly — genuinely over budget (7 and 4 real
layers respectively), not decorative, so `--fix` had nothing to correct.
Diffs elsewhere are heading text, `^{:stratum n}` metadata, and
def/deftest reordering only — no executable line changed. Notable
findings from the recomputed real dependency graph:

- `protocol.clj` declares two type sets and four independent protocols
  (`Operator`, `PatternDetector`, `ImprovementGenerator`, `Governance`)
  with no same-file reference between any of them. The old headings
  numbered them 0-4 sequentially by writing order; real stratum for
  every def is 0. Pre-fix `SL003` (5 layers) was a sequential-numbering
  artifact, fully resolved.
- `interface.clj` is a pure re-export/delegation layer: every `def`/`defn`
  calls straight into `core/*`, `intervention/*`, `consumer/*`, or
  `application/*` (required namespaces, not same-file defs). Real stratum
  for every def is 0.
- `core.clj` has genuine same-file structure, now 4 real layers (was
  reported as 6 pre-fix, 4 post-fix, still `SL003`). `default-config`,
  the signal helpers, the three pattern detectors, the three improvement
  generators, `SimpleGovernance`, and the two `create-llm-*` fallback
  constructors sit at real Layer 0 (no same-file def calls). The
  `SimplePatternDetector`/`SimpleImprovementGenerator`/`SimpleOperator`
  defrecords (calling Layer 0 helpers) land at Layer 1;
  `create-pattern-detector`/`create-improvement-generator` at Layer 2;
  `create-operator` (constructs a `SimpleOperator` via `->SimpleOperator`)
  at Layer 3. `--fix` moved the `extend-type SimpleOperator` block (a
  non-`def` top-level form) to the file's appendix, after all real
  layers — verified this does not break compile order: `SimpleOperator`
  is defined at Layer 1, well before the appendix runs.
- `consumer.clj` is the deepest real chain in `src`: 6 layers, driven by
  the cursor-file helpers (call the Layer-0 filename constants),
  `read-cursor`/`write-cursor!` (call the cursor-file helpers),
  `route-intervention!`, `consume-operator-dir!`, `consume-pass!`, and
  `start!` each one layer above what they call. `stop!` has no same-file
  dependency and landed at real Layer 0 alongside the polling constants,
  even though it is defined and used far from them in the file.
- `intervention.clj` is the single deepest chain in the component: 9 real
  layers, driven by a `(delay (load-intervention-config))` indirection —
  `intervention-config-resource` (L0) → `load-intervention-config` (L1)
  → `intervention-config` (L2) → the five config-derived defs (L3) →
  `intervention-types`/`target-types`/`lifecycle-states` plus
  `approval-required?`/`terminal-state?`/`intervention-target-type`/
  `build-intervention` (L4) → `valid-transition?`/`next-state` (also L4)
  → `valid-type?`/`valid-target-type?`/`valid-state?`/
  `validate-target-type-supported`/`supported-target-types`/
  `bounded-vocabulary?` (L5) → `validate-intervention-type`/
  `validate-target-type-known`/`transition` (L6) → `create-intervention`
  and every lifecycle-transition wrapper (`start-approval`, `approve`,
  `reject`, `dispatch`, `apply-result`, `verify`, `fail`) (L7) →
  `create-intervention!` (L8). Genuine complexity, not a labeling
  artifact — every def's real dependency count checked by hand against
  its rewritten position.
- `llm_improvement_generator.clj` / `llm_pattern_detector.clj`: the
  `parse-*-type` helpers have no same-file dependency and moved to real
  Layer 0 alongside `summarize-pattern`/`summarize-signal` (previously
  both were under a "Layer 1" banner below `build-*-prompt`, which
  itself calls `summarize-*` and so genuinely sits one layer above).
- `core_test.clj`: the fixture helpers (`signal`, `FakeLLMClient`) and
  every `deftest` calling neither them nor a pattern/rollback/repair
  signal helper landed at real Layer 0; `failure-signal-for`/
  `rollback-signal-from-to`/`repair-signal-for` (call `signal`) plus the
  two direct-LLM-constructor tests landed at Layer 1; the detector tests
  that call the phase-2 signal helpers landed at Layer 2. The old
  headings repeated "Layer 1"/"Layer 2"/"Layer 3" non-monotonically
  instead of separating these three real strata.
- `consumer_test.clj`: `find-workspace-root` (calls a Layer-0 constant)
  is Layer 1; `golden-dir` (calls `find-workspace-root`) is Layer 2;
  `stage-golden!` (calls `golden-dir`) is Layer 3; every `deftest` using
  `stage-golden!` or a golden fixture lands at Layer 4.
- `messages.clj`: gained its first real heading (`Layer 0` on the sole
  `def t`) — previously had no `Layer N` heading at all, the documented
  "no heading = silently skipped" limitation, not a clean bill of
  health.

No same-line trailing comment was displaced onto the wrong def, and no
stale decorative banner was left contradicting a real heading — checked
by reading the full diff and the full resulting content of all 18
changed files, not just the diff hunks. A `grep` for any leftover
`;; ... Layer N` or `Layer N:`-style banner across the component's `src`
and `test` trees came back empty.

## Testing Plan

1. Ran plain `stratum-lint` before the fix — reproduced the findings
   above exactly (14 findings this run vs. 13 in the 2026-07-24
   baseline; main had moved on in the interim, no material difference).
   Confirmed zero `SL001` before proceeding, per this component's stated
   risk (not pre-vetted as `SL001`-free).
2. Ran `--fix`, then a second `--fix` pass immediately after — no
   `rewrote` output, zero diff, confirms idempotency.
3. Read the full diff and the full resulting content for all 18 changed
   files. Confirmed only heading text, `^{:stratum n}` metadata, and
   def/deftest reordering changed; independently re-derived the expected
   real stratum for every def from its same-file reference graph and
   matched it against what `--fix` produced, including the 9-layer
   `intervention.clj` chain.
4. `clj-kondo --lint components/operator`: 0 errors, 0 warnings.
5. Ran the component's test namespaces directly (`clojure -M:dev:test`,
   requiring and running `core-test`, `defaults-test`, `protocol-test`,
   `intervention-test`, `interface-test`, `consumer-test`,
   `llm-improvement-generator-test`, `llm-pattern-detector-test`,
   `anomaly.create-intervention-test`, `application-test`,
   `mechanism-test` — the last two were untouched by `--fix` but included
   for full component coverage): 132 tests, 459 assertions, 0 failures,
   0 errors.
6. Re-ran plain `stratum-lint` after the fix:
   - `protocol.clj`, `interface.clj`, `defaults.clj`, `messages.clj`,
     `core_test.clj`, `defaults_test.clj`, `protocol_test.clj`,
     `interface_test.clj`, `intervention_test.clj`,
     `llm_improvement_generator_test.clj`, `llm_pattern_detector_test.clj`,
     `anomaly/create_intervention_test.clj`: **resolved**, all now
     within the 3-layer budget.
   - `application.clj` (7), `consumer.clj` (6), `core.clj` (4),
     `intervention.clj` (9), `llm_improvement_generator.clj` (5),
     `llm_pattern_detector.clj` (5), `mechanism.clj` (4),
     `consumer_test.clj` (5): still `SL003`, now precisely measured
     (some counts moved from the pre-fix numbers as the tool correctly
     merged/split what the old decorative headings had miscounted).
     Genuine over-budget files, Wave 2 scope (namespace split), not
     addressed here.

## Deployment Plan

Merges to `main` like any other component change. No runtime behavior
change — comment/metadata/order-only. Pre-commit's `lint:stratum`
autofixer keeps this component clean going forward; the eight files
still over budget stay advisory (`MINIFORGE_STRATUM_BUDGET_MODE=warn` at
commit time) until Wave 2 splits them.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace splits for `application.clj`,
  `consumer.clj`, `core.clj`, `intervention.clj`,
  `llm_improvement_generator.clj`, `llm_pattern_detector.clj`,
  `mechanism.clj`, and `consumer_test.clj` (all genuinely over the
  3-layer budget, confirmed real, not decorative).

## Checklist

- [x] Plain lint run first; confirmed zero `SL001` before proceeding
- [x] `--fix` run over the whole component (`src` + `test`)
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff and full file content read for all 18 changed files;
      mechanical-only, real stratum independently re-derived and checked
      for every def, including the 9-layer `intervention.clj` chain
- [x] `clj-kondo` clean (0 errors, 0 warnings)
- [x] Component test namespaces pass (132 tests, 459 assertions, 0
      failures/errors)
- [x] Plain lint re-run post-fix: 12 files resolved; 8 files remain
      `SL003` (genuine over-budget, documented above with precise counts,
      tracked as Wave 2)
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
