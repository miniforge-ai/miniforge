<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Preflight pipelines before any connector opens

## Overview

`execute-pipeline` now refuses a pipeline that names behavior which does not
resolve. The check runs before any connector opens or stage runs. This is
part of #2422.

## Motivation

The runner passed records through when a named transform was missing, and a
validate stage with no quality pack did the same. A missing sink connector was
found only when the publish stage ran, after the ingest stages had already
read their sources.

## Changes in Detail

1. New `pipeline-runner.preflight/check`, exposed as `pipeline-runner/preflight`.
   It checks, per stage:
   1. `:ingest` and `:publish`: a bound connector that implements the base
      `Connector` protocol and `SourceConnector` or `SinkConnector`.
   2. `:validate`: a quality pack with at least one rule, each with a
      `:rule/check-fn`.
   3. Every other family: a `:stage/transform` whose `:transform/type` is in
      context `:transforms`.
   4. Any stage: a `:stage/spawn-fn` registered in context `:spawn-fns`.
   5. A non-connector stage that skips processing on purpose declares
      `:stage/pass-through? true`. Declaring it together with a transform or
      a quality pack is a conflict, whatever the family.

   Every problem carries the stage id, a `:preflight/problem` kind and the
   missing `:preflight/ref`. All problems are reported, not only the first.
2. `execute-pipeline` runs preflight after structural validation and before it
   creates the run. A refusal returns schema/failure with
   `:preflight/problems` and no `:pipeline-run`.

## Behavior changes

1. A non-connector stage with no transform, no quality pack and no
   pass-through declaration is now refused. Before, it passed records through.
2. A pipeline whose connector is missing is refused before any stage runs.
   Before, earlier stages ran and the run failed at that stage, with a
   `:pipeline-run`.
3. The shipped `risk-data` pack is refused by preflight until the stacked ETL
   PR lands: the ETL runner supplies neither the built-in transforms nor a
   rule registry. On `main` the same pack already fails at run time, when its
   validate stage calls the nil rules the resolver produced.

## Review fixes

1. A connector that implements `SinkConnector` or `SourceConnector` without
   the base `Connector` protocol is refused (it would fail at `connect`).
2. Pass-through declared beside a transform is a conflict on a validate stage
   too, not only beside a quality pack.
3. The ETL wiring (built-in transforms and rules, `validate-pack` preflight,
   the `risk-data` pass-through declaration) moved to its own PR to keep this
   one under the PR budget.

Second round (findings Copilot reported as previously missed):

1. A transform, spawn fn or rule check fn may be a fn, a multimethod, or a
   bound Var holding one; `fn?` had refused Vars and multimethods the
   executor can call. An unbound Var, or one holding data, is refused.
2. Pass-through beside any `:stage/transform` declaration is a conflict, even
   one without a `:transform/type`.
3. A quality pack whose `:pack/rules` is not a list is reported as
   `:quality-pack/malformed` instead of throwing from `empty?`.

## Not in this PR

1. ETL wiring: the stacked ETL PR.
2. Stages spawned mid-run are not preflighted; the executor change that
   refuses them and records a pass-through receipt is a later PR, as is naming
   unresolved rules.
3. A rule that throws or returns a malformed verdict becoming an execution
   error, and the #2422 gate report.

## Testing Plan

1. `preflight_test`: one fixture per problem kind (unresolved transform, spawn
   fn, quality rule, validator without pack, empty pack, conflict on transform
   and validate stages, a source-only connector or a sink without `Connector`
   bound to publish), each asserting zero source and sink connector calls; all
   problems reported together; declared pass-through runs both connectors.
2. `interface_test`: fixtures declare pass-through; the tests that asserted
   silent pass-through or a mid-run missing-connector failure now assert the
   preflight refusal.

## Deployment Plan

No migration. A pipeline that relied on silent pass-through must declare
`:stage/pass-through? true` or register its transform.

## Depends on

Stacked on #2444, the mechanical split of `run.clj`.

## Related Issues/PRs

Part of #2422, under tracker #2421.

## Checklist

- [x] Preflight before any connector call, all problems reported.
- [x] Copilot findings on connector protocols and pass-through conflicts.
- [ ] ETL wiring (stacked PR).
