<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(workflow): delete the test-only max-redirects alias

## Overview

`execution-transition/max-redirects` was a `def` that copied
`(runner-defaults/max-redirects)` at namespace load. No production code
read it; only two tests did. This PR deletes it (rule 008) and points
both tests at `runner-defaults/max-redirects`, the value the redirect
guard enforces.

Stacked on #1924, which created `execution_transition.clj`. The base
branch is `refactor/split-workflow-execution`; retarget to `main` once
that PR merges.

## Motivation

The redirect ceiling is enforced in one place: the
`:budget/redirects-spent?` guard (`budget-redirects-spent?` in
`standard_guards_and_actions.clj`). It reads `:redirect-count` from FSM
context and compares it against `(defaults/max-redirects)`, called on
each check. The ceiling itself is not stored in FSM context.

The alias was a second name for the same number. Tests that pinned the
alias pinned a value nothing enforced, so a change to the guard's source
could leave them green.

## Changes in Detail

1. `execution_transition.clj`: delete `max-redirects` and the
   `runner-defaults` require it alone used. The `apply-phase-transition`
   docstring named the var; it now names `runner-defaults/max-redirects`.
2. `phase_transitions_test.clj`: `max-redirects-is-finite-and-positive`
   asserts `pos-int?` and `<= 100` on `(runner-defaults/max-redirects)`.
   The namespace already required `runner-defaults`.
3. `run7_regression_test.clj`: "max-redirects is now 5" asserts
   `(= 5 (runner-defaults/max-redirects))`. The `execution-transition`
   require, used only for the alias, is replaced by `runner-defaults`.

Both tests assert what they asserted before.

## Testing Plan

1. Whole-repo grep over tracked files (components, bases, projects,
   development, tasks, EDN, docs) for `max-redirects`: the only readers
   of the alias were the two tests above. No `resolve` or
   `requiring-resolve` reaches it by name.
2. `phase-transitions-test`, `run7-regression-test` and `fsm-test`:
   45 tests, 222 assertions, 0 failures. `fsm-test` already drives the
   guard against `(defaults/max-redirects)` through the compiled machine.
3. clj-kondo: 0 errors, 0 warnings on the three files.
4. `stratum-lint`: clean on all three files, and `--fix` leaves each
   unchanged.

## Related Issues/PRs

1. #1924: the `execution.clj` split that moved the alias into
   `execution-transition`. This PR's base.

## Checklist

- [x] No remaining reference to `execution-transition/max-redirects`
- [x] Both tests keep their assertions and read the enforced source
- [x] Every touched file is a `stratum-lint --fix` fixed point
