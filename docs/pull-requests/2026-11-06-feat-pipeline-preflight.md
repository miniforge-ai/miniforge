<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Preflight pipelines before any connector opens

## Overview

`execute-pipeline` and `etl validate` now refuse a pipeline that names behavior
which does not resolve. The check runs before any connector opens or stage
runs. Part of #2422.

## Motivation

The runner passed records through when a named transform was missing, and a
validate stage with no quality pack did the same. The resolver mapped an
unknown quality rule to nil.

The shipped `risk-data` pack hit all of this. The ETL runner supplied neither
the built-in transforms nor a rule registry. So `Derive Buffett Indicator`
passed records through unchanged, and both `Validate Quality` rules resolved to
nil, which the data-quality evaluator then calls as a function.

A missing sink connector was found only when the publish stage ran, after the
ingest stages had already read their sources.

## Changes in Detail

1. New `pipeline-runner.preflight/check`, exposed as `pipeline-runner/preflight`.
   It checks, per stage:
   1. `:ingest` and `:publish`: a bound connector that implements
      `SourceConnector` or `SinkConnector`.
   2. `:validate`: a quality pack with at least one rule, each with a
      `:rule/check-fn`.
   3. Every other family: a `:stage/transform` whose `:transform/type` is in
      context `:transforms`.
   4. Any stage: a `:stage/spawn-fn` registered in context `:spawn-fns`.
   5. A non-connector stage that skips processing on purpose declares
      `:stage/pass-through? true`. Declaring it together with processing is a
      conflict.

   Every problem carries the stage id, a `:preflight/problem` kind and the
   missing `:preflight/ref`. All problems are reported, not only the first.
2. `execute-pipeline` runs preflight after structural validation and before it
   creates the run. A refusal returns schema/failure with
   `:preflight/problems` and no `:pipeline-run`.
3. ETL runner: `run-pack` registers the built-in transforms and resolves rules
   against the built-in rule registry. `validate-pack` preflights the resolved
   pipeline.
4. `risk-data` pack: `Normalize` declares `:stage/pass-through? true`. It only
   merges upstream datasets.

## Behavior changes

1. A non-connector stage with no transform, no quality pack and no
   pass-through declaration is now refused. Before, it passed records through.
2. A pipeline whose connector is missing is refused before any stage runs.
   Before, earlier stages ran and the run failed at that stage, with a
   `:pipeline-run`.
3. In the `risk-data` pack, `Derive Buffett Indicator` now computes its ratio
   and `Validate Quality` now evaluates its two rules.

## Not in this PR

Stages spawned mid-run are not preflighted. The executor still passes their
records through when they declare no processing; the next PR makes it refuse
them and records a pass-through receipt on the stage run. The resolver still
maps a rule it cannot resolve to nil; preflight refuses it but cannot name it
until the resolver keeps the descriptor (next PR). Rule errors (a rule
that throws or returns a malformed verdict) are also a later PR. The #2422
gate report waits for the shared qualification harness.

## Testing Plan

1. `preflight_test`: one fixture per problem kind (unresolved transform, spawn
   fn, quality rule, validator without pack, empty pack, conflict, wrong
   connector role), each asserting zero source and sink connector calls; all
   problems reported together; declared pass-through runs both connectors.
2. `interface_test`: fixtures declare pass-through; the tests that asserted
   silent pass-through or a mid-run missing-connector failure now assert the
   preflight refusal.
3. ETL `runner_test`: all three shipped packs pass `validate-pack` with
   preflight; a pack naming an unknown transform fails it.

## Deployment Plan

No migration. A pipeline that relied on silent pass-through must declare
`:stage/pass-through? true` or register its transform.

## Depends on

Stacked on #2444, the mechanical split of `run.clj`.

## Related Issues/PRs

Part of #2422, under tracker #2421.

## Checklist

- [x] Preflight before any connector call, all problems reported.
- [x] Shipped packs still validate.
- [ ] Executor refusal and pass-through receipt for spawned stages (next PR).
