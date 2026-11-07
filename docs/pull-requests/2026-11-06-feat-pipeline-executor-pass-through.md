<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Executor refuses undeclared pass-through; resolver names unknown rules

## Overview

Two follow-ups to the pipeline preflight. The stage executor now refuses a
stage with no processing and no pass-through declaration, and records a
receipt when a stage passes through on purpose. The resolver keeps a quality
rule it cannot resolve as its descriptor, so preflight can name it.
This is part of #2422.

## Motivation

Preflight checks the stages a pipeline declares. A stage spawned mid-run is
created after preflight, and the executor still passed its records through
when it named no transform. The issue also asks that an intentional
pass-through leave an auditable receipt.

The resolver mapped an unresolvable rule to nil. Preflight refused it, but
reported a nil ref, so the operator could not tell which rule was missing.

## Changes in Detail

1. `processing-stage`:
   1. A transform-family stage with no resolved transform, and a validate
      stage with no quality pack, fail with `:error-kind :configuration`
      unless they declare `:stage/pass-through? true`.
   2. A declared pass-through completes with `:pass-through? true` on its
      stage run.
   3. A validate stage no longer falls through to the transform executor.
2. `scheduler/run-stage`: `execute-validate-stage` no longer takes the context.
3. `pipeline-config`: rule hydration moves to a new `rule-resolution`
   namespace. A rule the registry cannot resolve stays as its descriptor,
   without a `:rule/check-fn`. `resolver.clj` drops its unused `messages`
   require and gets the Apache header; the split keeps both files within three
   strata.

## Review fixes (Copilot)

1. A spawned stage that declares pass-through beside a transform or quality
   pack fails as a conflict, so a named transform with no registered fn can no
   longer fall through to pass-through.
2. A spawned validate stage whose quality pack has no rules fails instead of
   passing every record.

## Behavior changes

1. A spawned stage that declares no processing fails instead of passing its
   records through.
2. A validate stage with a `:stage/transform` and no quality pack no longer
   runs the transform. Preflight already refused such a stage.
3. Preflight's `:quality-rule/unresolved` problem names the rule's id or type
   instead of nil.

## Not in this PR

A rule that throws, or returns a malformed verdict, is still not turned into
an execution error. That is the next #2422 PR.

## Testing Plan

1. `preflight_test`: a declared pass-through on a normalize and on a validate
   stage records `:pass-through? true`; a spawn fn that adds a transform-family
   child with no processing fails that child with `:configuration`, and the
   sink is never called.
2. `resolver_test` (new): unresolvable descriptors stay as data; a built-in
   rule hydrates beside an unknown one.
3. Locally, `pipeline-runner`, `pipeline-config` and ETL namespaces: 86 tests,
   257 assertions, 0 failures.

## Deployment Plan

None beyond the preflight PR's: pipelines that skip processing declare
`:stage/pass-through? true`.

## Depends on

Stacked on #2446 (preflight), which is stacked on #2444.

## Related Issues/PRs

Part of #2422, under tracker #2421.

## Checklist

- [x] Spawned stages cannot pass through silently.
- [x] Pass-through receipt on the stage run.
- [x] Unresolved rules named in preflight problems.
