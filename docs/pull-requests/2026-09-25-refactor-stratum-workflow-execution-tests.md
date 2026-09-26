<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# test: bring the execution tests under the layer budget

## Overview

Prepares four test files for the `execution.clj` split, which edits
each of them. All four end at three strata or fewer and are fixed
points of `stratum-lint --fix`, so the split's commits change only the
lines it needs.

## Motivation

The pre-commit hook runs `stratum-lint --fix` on every staged `.clj`
file and blocks on SL003. Without this PR, the split would either stall
or carry these rewrites mixed into its own diff:

| file | before | blocker |
|---|---|---|
| `phase_transitions_test.clj` | four strata on main | SL003, any edit blocks |
| `execute_enter_error_propagation_test.clj` | no headings | `--fix` lands it at four strata |
| `dag_activation_diagnostics_test.clj` | no headings | `--fix` regroups the whole file |
| `artifact_persistence_test.clj` | no headings | `--fix` regroups the whole file |

## Changes in Detail

1. **Two fixture helpers inlined.** Each over-budget file had one
   single-caller helper adding a level. `policy-pack` is now inlined in
   `policy-review-context`. `throwing-enter-fn` is inlined in the two
   interceptor builders.
2. **Headings from `--fix`.** The three unannotated files get Layer
   headings and `^{:stratum n}` tags, and their forms are regrouped by
   stratum.
3. **Section comments repaired.** After regrouping, old dashed banners
   ("Mock Data", "dag-skip-diagnostic", ...) sat above forms they did
   not describe. Each is now a plain `;;` comment on its group.

## Testing Plan

1. Top-level forms compared before and after, ignoring order and
   metadata. The only differences are the two inlined helpers and their
   callers. Forms containing regex literals always compare unequal,
   because `Pattern` equality is identity; their printed text is
   identical.
2. `phase-transitions-test`, `execute-enter-error-propagation-test`,
   `dag-activation-diagnostics-test`: 34 tests / 98 assertions pass.
3. `artifact-persistence-test` (projects/miniforge): 9 tests / 21
   assertions pass.
4. `stratum-lint` plain and `--fix`: clean, no rewrite.

## Related Issues/PRs

- The `execution.clj` split (rule 210), stacked on this PR.

## Checklist

- [x] No assertion changed
- [x] Every touched file at three strata or fewer, and a `--fix` fixed point
- [x] Section comments describe the forms beneath them
