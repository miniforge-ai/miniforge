<!--
  Title: Split policy-pack detection.clj — extract detection.custom-registry (2/2)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split detection.clj — extract detection.custom-registry (2/2)

## Overview

Slice 2 of the `detection.clj` rule-210 split (see PR 1/2, merged as
`ff92b0b1b6802ab3e52158ac95d6708873e2ad15`, #1761). Extracts
`ai.miniforge.policy-pack.detection.custom-registry`: the custom-fn
extension-point registry atom and the reflection helpers that decide
whether a candidate fn is a valid 2-arity detector —
`custom-fn-registry`, `declared-method?`, `reflectable-invoke?`,
`variadic-accepts-arity?`, `resolve-custom-fn`, `detector-predicate?`.
3 real layers, stratum-lint clean.

This is the **final slice** of this train: with these six functions
moved, `detection.clj` itself drops from 4 real layers to 3 — at the
rule 210 budget. Confirmed by hand-computing each remaining def's real
same-file dependency depth ahead of the move (cross-namespace calls no
longer count toward a file's own layer depth once the callee moves out),
then verifying with a scratch `stratum-lint --fix` dry-run before
committing, and writing the ns docstring's layer summary to match the
verified post-fix state directly (rather than leaving a "stale pending
next commit" placeholder — the first PR of this train left one of those
and Copilot correctly flagged it as misleading, see below).

## Motivation

Part of the stratum-lint rule-210 remediation program's Wave 2,
policy-pack batch 2. Continues from PR 1/2, which brought `detection.clj`
from 6 real layers to 4 by extracting the pattern-matching/plan-parsing
primitives into `detection.matching`, and picked up one review fix
(correcting that same PR's stale ns docstring) before merging.

## Changes in Detail

- New file `detection/custom_registry.clj`: the six functions named
  above, unchanged bodies, plus two new ones added in a review-fix
  commit (see below). 3 layers (L0: `custom-fn-registry`,
  `declared-method?`, `reflectable-invoke?`; L1: `variadic-accepts-arity?`,
  `resolve-custom-fn`, `register!`, `unregister!`; L2: `detector-predicate?`).
  - `resolve-custom-fn` and `detector-predicate?` are public (were
    `^:private`/`defn-` in the combined namespace) because `detection.clj`
    is now a cross-namespace caller of both.
  - `custom-fn-registry` stays `^:private` — never public. A first cut
    of this slice made it public so `detection.clj` could `swap!` it
    directly; Copilot review correctly flagged that as an encapsulation
    regression this split introduced (the atom was never reachable
    outside its own file before), so a follow-up commit added
    `register!`/`unregister!` here as the mutation API and put the atom
    back behind it.
- `detection.clj`: the six original functions removed.
  `detect-custom`/`custom-fn-resolvable?`/`detect-violation` now call
  `custom-registry/resolve-custom-fn` across the namespace boundary.
  `register-custom-fn!` validates (unchanged: `symbol?` and
  `custom-registry/detector-predicate?` checks, throwing `ex-info`) then
  calls `custom-registry/register!`; `unregister-custom-fn!` calls
  `custom-registry/unregister!`. Neither touches the registry atom
  itself anymore. `register-custom-fn!`, `unregister-custom-fn!`,
  `custom-fn-resolvable?`, and `detect-custom` themselves stay in
  `detection.clj`, unchanged in name/signature — they're the "use the
  registry" half of the custom-fn extension point; the "store/validate
  the registry" half moved.
  Ran `stratum-lint --fix` (folded into the pre-commit hook's
  `lint:stratum` step, same as PR 1/2) to renumber the remaining defs:
  4 → 3 real layers. The ns docstring was written to the correct final
  state directly in the code-motion commit, not left stale, and updated
  again in the review-fix commit to describe the encapsulated registry.
- Fan-in re-checked via the fully-qualified namespace grep
  (`ai\.miniforge\.policy-pack\.detection\b`). All 6 originally-moved
  symbols were `defn-`/`^:private` in the combined namespace, so **no
  external caller referenced them directly** — confirmed with
  `grep -rnE 'declared-method\?|reflectable-invoke\?|variadic-accepts-arity\?'`
  across `components`/`bases`/`projects`, which found only
  self-references inside the new file (plus this namespace's own
  docstring prose). None of the files that reference
  `ai.miniforge.policy-pack.detection` needed call-site updates for this
  slice.

This is pure code motion for the six originally-moved functions — no
detection logic changed, def set unchanged except location and two
visibility flips (`resolve-custom-fn`, `detector-predicate?` private →
public). The two new `register!`/`unregister!` functions and the
`custom-fn-registry` visibility reversal are a real (small) design
change, added in response to review, not part of the original
code-motion claim.

## Testing Plan

- `stratum-lint` clean (exit 0) on all three files:
  `detection.clj` (now 3 real layers, down from 6 originally — **at
  budget**), `detection/matching.clj`, `detection/custom_registry.clj`.
- `clj-kondo` clean on both touched/added files.
- Ran every policy-pack test namespace that requires
  `ai.miniforge.policy-pack.detection`, from repo root (
  `standard-packs-test` reads a repo-root-relative fixture path, a
  lesson carried over from PR 1/2's testing notes):
  - `ai.miniforge.policy-pack.detection-test`: 18 tests / 91 assertions
  - `ai.miniforge.policy-pack.ast-test`: 7 tests / 22 assertions
  - `ai.miniforge.policy-pack.intent-test`: 9 tests / 33 assertions
  - `ai.miniforge.policy-pack.builtin-detectors-test`: 1 test / 13 assertions
  - `ai.miniforge.policy-pack.compiler-test`: 19 tests / 64 assertions
  - `ai.miniforge.policy-pack.standard-packs-test`: 10 tests / 399 assertions
  - All 0 failures, 0 errors.
  - The pre-commit hook's own `test:precommit`/`test:graalvm` passed on
    both commits.
- Adversarial self-review: diffed the top-level `defn`/`def` set before
  and after — 6 relocated with 0 altered behavior, 2 new functions
  (`register!`/`unregister!`, added in the review-fix commit), 2
  visibility flips (`resolve-custom-fn`/`detector-predicate?` private →
  public) plus one visibility reversal (`custom-fn-registry` briefly
  public, put back to private in the review-fix commit); every other
  call site update is a namespace-qualification change only.

PR size: four commits (62, 89, and 43 reportable lines for the
code-motion/review-fix commits respectively, plus a doc-only commit;
the pre-commit hook's `lint:stratum` autofix re-stages beyond what
`bb commit-budget` measures on the pre-hook staged diff, same pattern
as PR 1/2 — not a budget violation). All comfortably under the 600-line
PR budget. `MINIFORGE_STRATUM_BUDGET_MODE=warn` used on the code-motion
commit for the plain-lint pre-commit gate, given `detection.clj` was
still (briefly, pre-autofix) over budget mid-commit.

Note: this PR was originally opened as #1765, stacked on #1761 before
that PR merged. Rebasing the stacked branch onto the post-squash-merge
`main` produced a real conflict (the squash-merged commit's diff didn't
patch-match the pre-squash commits cleanly), so the branch was rebuilt
from a fresh worktree off the merged `main` instead of resolving the
rebase conflict in place — same code, same commits in spirit, clean
history against the actual merge base.

## Deployment Plan

Merges to `main` as the final PR of this train.
`ai.miniforge.policy-pack.detection` is now stratum-lint compliant (3
real layers, budget 3), alongside its two siblings `detection.matching`
and `detection.custom-registry`. No further slices needed for this file.

## Related Issues/PRs

- Part 1/2 of this train: `refactor(policy-pack): split detection.clj —
  extract detection.matching (1/N)`, merged as
  ff92b0b1b6802ab3e52158ac95d6708873e2ad15 (#1761)
- Precedent: [mdc_compiler.clj split, #1729-#1743](https://github.com/miniforge-ai/miniforge/pull/1729)
- Precedent: [workflow_runner.clj split, #1662-#1667](https://github.com/miniforge-ai/miniforge/pull/1662)
- Part of the stratum-lint rule-210 remediation program (Wave 2, policy-pack batch 2)

## Checklist

- [x] Fan-in confirmed via fully-qualified-namespace grep before starting;
      zero external call-site updates needed (all moved symbols were
      already private)
- [x] Pure code motion for the six originally-moved functions; the
      registry-encapsulation follow-up (2 new fns, 1 visibility
      reversal) is a documented, review-driven exception
- [x] `clj-kondo` clean
- [x] Tests green across all 6 affected namespaces (run from repo root)
- [x] PR-diff and commit-diff budgets checked
- [x] `MINIFORGE_STRATUM_BUDGET_MODE=warn` used + documented for the
      expected intermediate over-budget state
- [x] `detection.clj` confirmed at 3 real layers (target reached) via
      `stratum-lint` post-commit
