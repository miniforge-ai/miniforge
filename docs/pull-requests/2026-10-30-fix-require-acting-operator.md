<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(tenancy): refuse unowned runs when [:tenancy :require-operator?] is set

PR #2265.

## Overview

Adds `[:tenancy :require-operator?]`. When set, a run with no valid operator identity never starts, and a resumed run
whose snapshot carries no acting context is refused.

## Motivation

With no configured operator, `resolve-acting` returned nil and the run proceeded with no owning identity.
`tenancy/require-acting`, which refuses instead of substituting a default, had no callers outside the tenancy component.
An installation that needs every run attributed had no way to enforce it.

## Changes in Detail

1. Refactor (no behavior change): `anomaly-category` and `throw-resume-anomaly!` move from `commands/resume.clj` to
  `commands/resume_anomaly.clj`. `resume.clj` spanned four strata, so stratum-lint refused any edit to it.
2. Config schema gains `[:tenancy :require-operator?]` (boolean).
3. CLI `resolve-acting`: with the key set, an absent or invalid operator throws an `:invalid-input` anomaly naming the
  key, before the `workflow-started` event is published.
4. `create-workflow-context` passes `:require-operator? true` to the workflow runner when the key is set. Workflow
  `build-initial-context` then calls `tenancy/require-acting` on the fresh run's `:acting` and returns an
  `:invalid-input` anomaly before any phase runs; the runner turns that into a failed run.
5. The resume command checks the recorded snapshot's `:execution/acting` with `tenancy/require-acting` right after
  reading it, before `--pin` or the runner can write anything, and throws when the key is set. The runner does not check
  resumes: it would persist its refusal over the run's checkpoint.

Unset, behavior is unchanged.

Not covered: runs started through the configurable-workflow entry point (`workflow/execute-workflow`) and any caller
that builds runner opts without `create-workflow-context`; they do not pass the flag. No CLI command uses that entry
point today.

## Testing Plan

1. `required_operator_test` (workflow): with the flag, no `:acting` or an invalid one is refused; a valid one is
  admitted and lands on `:execution/acting`; the runner leaves resumes to the resume command; without the flag, unowned
  runs still build. Fails on main.
2. `resume_test`: with the flag, a resume whose snapshot has no acting context throws before the runner runs and pins nothing.
3. `context_test` (CLI): with the flag, no operator and a blank operator both refuse to start; a configured operator
  yields an acting context and the flag. Fails on main (4 failures).
4. Ran locally: workflow build-initial-context and required-operator tests; CLI context, resume and config-schema tests.
  All pass.

## Deployment Plan

Opt-in. Set `[:tenancy :operator-name]`, then `[:tenancy :require-operator?] true`.

## Related Issues/PRs

Part of the effect-authority work alongside #2258 and #2262.

## Checklist

- [x] Regression tests fail on main
- [x] Commits under 200 reportable lines; PR under 600
- [x] PR doc: `docs/pull-requests/2026-10-30-fix-require-acting-operator.md` (rule 721), added after merge
