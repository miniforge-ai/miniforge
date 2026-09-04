# refactor(workflow): stratum headings for the runner test namespaces (rule 210)

## Overview

Mechanical output of `stratum-lint --fix` over six test namespaces that
predate the pre-commit stratum gate (2026-08-10) and carried no `Layer N`
headings. No def body changes: the tool tags each def with its inferred
`^{:stratum n}` metadata, regroups defs under regenerated headings, and
nothing else.

## Motivation

The pre-commit hook (`bb lint:stratum`, `lint/stratum-staged`) autofixes
and re-stages every staged Clojure file. The first commit to touch one of
these files after 2026-08-10 therefore carries the whole regroup along
with whatever it meant to change. Landing the regroup on its own keeps
the functional PR that follows
(`fix/test-checkpoint-root-fixture`) reviewable as the ~100-line change
it is.

## Changes in Detail

Workflow brick (`components/workflow/test/ai/miniforge/workflow/`):

1. `runner_extended_test.clj`
2. `runner_iteration_test.clj`
3. `environment_promotion_integration_test.clj`
4. `run7_regression_test.clj`
5. `anomaly/build_initial_context_test.clj`

Project `miniforge` (`projects/miniforge/test/ai/miniforge/workflow/`):

1. `runner_integration_test.clj`

Verification that the diff is metadata and ordering only: for each file,
the multiset of non-comment, non-blank lines with `^{:stratum n}` stripped
is identical before and after.

## Testing Plan

1. `bb lint:stratum` over the staged set reports no findings.
2. `clojure -M:poly test brick:workflow` green.
3. `bb test:integration` namespaces `runner-integration-test` green.

## Deployment Plan

Test-only, behaviour-neutral. No runtime code touched.

## Related Issues/PRs

Foundation for `fix/test-checkpoint-root-fixture`, which adds a shared
temp-checkpoint-root fixture to these same namespaces.

## Checklist

- [x] Autofix output only, verified line-multiset identical
- [x] Lint clean
- [x] Tests green
