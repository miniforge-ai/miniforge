<!--
  Title: Split policy-pack/intent.clj (rule 210)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(policy-pack): split intent.clj (rule 210)

## Overview

Splits the full semantic-intent check out of
`ai.miniforge.policy-pack.intent` into a new sibling namespace,
`ai.miniforge.policy-pack.intent.check`, resolving a stratum-lint
SL003 finding (the combined namespace measured 4 real layers, over
the rule 210 budget of 3).

## Motivation

Part of the stratum-lint rule-210 remediation program, policy-pack
Wave 2 batch 2. `intent.clj` (218 lines) has real fan-in — before
this split, three files repo-wide referenced
`ai.miniforge.policy-pack.intent` (its own test, its
`interface/intent.clj` re-export, and itself) — so this is a real
split, not a zero-fan-in cleanup. The split itself adds a 4th
reference: the new `intent/check.clj` now also requires
`ai.miniforge.policy-pack.intent` to call `infer-intent` and
`intent-matches?`.

## Changes in Detail

- New file `intent/check.clj` (namespace
  `ai.miniforge.policy-pack.intent.check`): `semantic-intent-check`,
  moved verbatim — 1 layer. It requires `ai.miniforge.policy-pack.intent`
  and calls `intent/infer-intent` + `intent/intent-matches?`
  cross-namespace, so it no longer stacks on top of the parent's own
  layers.
- `intent.clj`: keeps `intent-types`, `infer-intent`,
  `intent-constraints`, `violation-message-fmt`,
  `parse-terraform-plan-counts`, `parse-k8s-diff-counts` (Layer 0),
  `intent-violation` (Layer 1), and `intent-matches?` (Layer 2) — now
  3 layers (down from 4). Docstring updated to point at the new
  namespace instead of describing a Layer 3 that no longer lives here.
- `interface/intent.clj`: added a require for
  `ai.miniforge.policy-pack.intent.check`; the `semantic-intent-check`
  pass-through def now points at `intent-check/semantic-intent-check`
  instead of `intent/semantic-intent-check`. No public def added,
  removed, or renamed — the interface's surface is unchanged.
- `intent_test.clj`: added a require for
  `ai.miniforge.policy-pack.intent.check`; the two
  `semantic-intent-check-test` assertions call `check/semantic-intent-check`
  instead of `sut/semantic-intent-check`. No other test changed.

Pure code motion — no logic changes. The moved function body is
byte-identical; only its namespace and the two call sites that invoke
it directly (test + interface) changed.

## Testing Plan

- `stratum-lint --fix` then plain `stratum-lint` clean (exit 0) on all
  three touched/new source files — was SL003 exit 1 on the original
  `intent.clj`.
- Repo-wide grep for the fully-qualified namespace
  (`ai\.miniforge\.policy-pack\.intent\b`, not a guessed symbol/alias
  prefix — a prior mistake in this program missed aliased callers)
  across `components`, `bases`, and `projects`, run before starting
  this split, found exactly the three pre-existing files listed in
  Motivation above; no caller in `projects/miniforge/test/`
  references this namespace, so there was no project-level integration
  test to update. (The new `intent/check.clj` created by this split
  is itself a 4th, expected reference — not a missed caller.)
- `bb test` (change-scope) ran green, but its "changed since stable
  tag" detection did not pick up this uncommitted worktree diff at all
  (it ran unrelated `workflow`/`workspace` component tests, zero
  `policy-pack` tests) — not sufficient on its own to validate this
  change.
- Directly verified instead: `clojure -M:dev:test -e "(require
  'ai.miniforge.policy-pack.intent-test) (clojure.test/run-tests
  'ai.miniforge.policy-pack.intent-test)"` → 9 tests, 33 assertions, 0
  failures, 0 errors.
- Also directly required `ai.miniforge.policy-pack.intent.check` and
  `ai.miniforge.policy-pack.interface.intent` and called
  `semantic-intent-check` through both the new namespace and the
  unchanged interface facade to confirm identical return values.

## Deployment Plan

Merges to `main` immediately; no follow-up needed for this file.

## Related Issues/PRs

- Part of the stratum-lint rule-210 Wave 2 continuation (see
  `workflow_runner.clj` splits miniforge#1662-#1667,
  `knowledge_safety.clj` split #1731, and the `compliance-scanner`
  split #1580 for the established convention this follows).

## Checklist

- [x] stratum-lint clean on all resulting files
- [x] `bb test` green (change-scope; did not cover this brick, see
      Testing Plan) plus a direct `clojure -M:dev:test` run of
      `intent-test` (9 tests / 33 assertions / 0 failures)
- [x] Adversarial self-review: def set unchanged (relocated only), no
      logic changes
- [x] Fan-in confirmed via fully-qualified namespace grep across
      components, bases, and projects — three callers, all updated
- [x] `tasks/pr_budget.clj` / `tasks/commit_budget.clj` ceilings not
      approached (diff is ~90 lines across 4 files)
