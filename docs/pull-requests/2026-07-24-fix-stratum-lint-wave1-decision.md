# fix: stratum-lint autofix for components/decision (Wave 1)

## Overview

Runs the pinned `stratum-lint --fix` over all four `.clj` files in
`components/decision` (3 src, 1 test) and commits the result. Regroups
each file's top-level defs under `;---- Layer N` headings that match the
tool's own same-file reference-graph inference, and adds `^{:stratum n}`
metadata to every def. No logic changes.

## Motivation

Wave 1 of `work/stratum-lint-baseline-2026-07-24.md`: `decision` carried 7
findings in the full-tree baseline, all SL002 (a `Layer 1` heading reused
as a repeated section banner rather than one heading per real stratum) and
zero SL001 (no upward-reference or reference-cycle risk to reason about
first) — which is exactly why the baseline plan puts it in the first
autofix batch, alongside `compliance-scanner`, `reliability`, `gate`, and
`adapter-claude-code`, before the larger/riskier components. Rule 210
(`standards/miniforge/languages/clojure.mdc`) requires headings that
reflect a real one-way dependency DAG (Layer 0 = pure/lowest, higher
layers may call lower/same layers only, max 3 layers/file); the baseline
audit found this convention had been cargo-culted into decorative section
breaks across most of the tree rather than true strata boundaries.

## Changes in Detail

- `components/decision/test/ai/miniforge/decision/interface_test.clj` —
  the file carrying all 7 pre-fix findings: `Layer 1` was reused seven
  times as a banner between unrelated `deftest` groups (enum exposure,
  validation helpers, `create-checkpoint`, `resolve-checkpoint`,
  loop-escalation, `create-episode`/`update-episode`, schema validation).
  `--fix` recomputed each `deftest`'s real stratum from the reference
  graph — none of the test fns call each other except the three fixture
  helpers (`valid-control-plane-checkpoint`, `valid-approve-response`,
  `loop-state`) that every `deftest` in the file depends on — and
  collapsed the headings to two real layers: 0 (the fixtures) and 1 (every
  `deftest`, all leaves relative to each other). Zero findings after.
- `components/decision/src/ai/miniforge/decision/interface.clj` — zero
  pre-fix findings, but `--fix` still rewrote it: added `^{:stratum n}`
  metadata to all 24 defs, and folded a decorative `Layer 1`/`Layer 2`
  split (labeled "Validation helpers" vs "Public constructors") into the
  real 2-layer structure — Layer 0 is schema re-exports plus the
  validation helpers (`valid?`/`explain`/`validate-result`/`validate`, all
  pure delegation to `spec`), Layer 1 is the public constructors, which
  call Layer 0's `validate`. Zero findings before and after.
- `components/decision/src/ai/miniforge/decision/core.clj` — zero pre-fix
  findings (the existing Layer 0/1/2 heading order was not itself wrong),
  but the reference graph the tool infers is deeper than that 3-layer
  labeling admitted: `decision-response` and two proposal/uncertainty
  helpers, previously placed under Layer 0 next to pure value defs, are
  called *by* the two checkpoint-constructor wrappers
  (`create-control-plane-checkpoint`, `create-loop-escalation-checkpoint`)
  by way of a Layer-2 input-builder — landing those wrappers at a genuine
  Layer 3. Post-fix the file reports **SL003: 4 distinct layers (max 3)**.
  This is a real over-budget finding, not a decorative one — needs an
  actual namespace split, which is Wave 2 work and out of scope here.
  Note: the `ns` docstring still says "Layer 0 / Layer 1 / Layer 2" —
  `--fix` only rewrites headings and metadata, never prose — so that
  docstring is now stale relative to the real 4-layer structure. Left
  as-is rather than hand-edited, to keep this diff exactly what the
  autofixer produced; worth folding into whichever Wave 2 PR splits this
  namespace.
- `components/decision/src/ai/miniforge/decision/spec.clj` — same shape,
  worse: real reference graph resolves to **6** layers (0: enums plus
  `valid?`/`explain`; 1: `registry` plus `validate-result`; 2: per-field
  schemas plus `validate`; 3: `DecisionProposal`/`DecisionContext`; 4:
  `DecisionCheckpoint`; 5: `DecisionEpisode`). Also **SL003: 6 distinct
  layers (max 3)**, also Wave 2. Same stale-docstring note applies (still
  says Layer 0/1/2). One structural side effect worth calling out
  explicitly: `valid?`/`explain`/`validate-result`/`validate` previously
  sat after the trailing `(comment ...)` block at the very end of the
  file — an odd pre-existing placement, not something this PR introduced.
  `--fix` moved them up into their correctly-inferred early layers, so the
  `(comment ...)` block is now genuinely the file's last form. Content of
  that block is byte-identical, only its relative position changed.

No file outside `components/decision` touched.

## Testing Plan

1. Read every changed file's full diff (`git diff`, all four files, not
   just `--stat`). Confirmed the changes are heading regrouping,
   `^{:stratum n}` metadata, and — in `core.clj`/`spec.clj` — def
   reordering to match the tool's inferred reference graph. No logic
   edits found.
2. Wrote a structural-equivalence check (babashka script): read every
   top-level form from the pre-fix and post-fix version of each file,
   strip metadata recursively (`clojure.walk`), and compare the four
   before/after form sets as order-independent frequency multisets. All
   four files: identical. (One apparent mismatch surfaced in
   `interface_test.clj` — traced to a limitation of the comparison method
   itself, not the fix: `java.util.regex.Pattern` has no value equality,
   so two independently-read `#"..."` literals with identical source text
   are never `=`. Direct textual diff of that one `deftest` confirmed it's
   byte-identical apart from the added `^{:stratum n}` metadata.)
3. Ran the actual test suite for the touched namespace (`bb test`,
   stable-derived changed+affected bricks):
   `ai.miniforge.decision.interface-test` — 43 tests, 116 assertions, 0
   failures, 0 errors.
4. Re-ran `stratum-lint` in plain (non-`--fix`) mode over
   `components/decision` afterward. `interface.clj` and the test file:
   zero findings. `core.clj` and `spec.clj`: one SL003 finding each (4 and
   6 real layers, both over the 3-layer budget) — expected per the
   baseline plan's Wave 2 (real namespace splits), not a failure of this
   PR.

Aside, not blocking: `bb test`'s affected-brick sweep also pulled in
`ai.miniforge.pr-lifecycle.monitor-worklist-test`, which errors 5/11 tests
with a `java.util.RegularEnumSet`/`HashSet` `ClassCastException` inside
`babashka.fs`'s `delete-tree` (a JDK-version-sensitive bug in a
third-party file-walk helper used for test cleanup). Confirmed
pre-existing and unrelated: reproduced identically with this branch's
changes stashed against the same base commit, and no `pr-lifecycle` file
is touched by this PR. Also not part of the pre-commit smoke set
(`resources/precommit-smoke-tests.edn` lists neither `pr-lifecycle` nor
`decision` namespaces), so it doesn't gate this commit.

## Deployment Plan

Merges to `main`. Pure source-formatting normalization (headings +
metadata) with no behavior change and no callers outside the four touched
files — nothing to roll out or monitor.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Depends on: #1459 (stratum-lint pre-commit autofix wiring, already
  merged) — reuses the same pinned sha
- Enables: `work/stratum-lint-baseline-2026-07-24.md`'s Wave 2 (real
  namespace splits) for `core.clj` (4 real layers) and `spec.clj` (6 real
  layers), both now confirmed via SL003 rather than assumed

## Checklist

- [x] Full diff read for all 4 changed files; confirmed mechanical
      (headings + `^{:stratum n}` metadata + reordering only, no logic
      changes)
- [x] Structural form-equivalence verified programmatically
      (order-independent, metadata-stripped comparison)
- [x] Component test suite green: `decision.interface-test` 43/43,
      0 failures, 0 errors
- [x] Post-fix plain lint: zero findings on `interface.clj` and the test
      file; SL003 remainder on `core.clj`/`spec.clj` documented as
      expected Wave 2 work, not a defect in this PR
- [x] Unrelated pre-existing `pr-lifecycle` test flake investigated and
      ruled out as caused by this change
