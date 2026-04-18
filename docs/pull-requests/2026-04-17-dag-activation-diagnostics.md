<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# DAG activation diagnostics — surface skip reasons in the event log

## Context

Three miniforge runs today ended with the curator's "implementer wrote no
files to the environment" after the implementer agent hung or streamed
with zero writes. The root-cause hypothesis (per the spec at
`work/plan-from-agent-dag-wiring.spec.edn`) was that the DAG orchestrator
never fires for LLM-generated plans, forcing a monolithic `:implement`
phase.

A REPL trace this session invalidated part of that hypothesis: with the
planner's real output shape, `dag-applicable?` DOES correctly return the
plan. So either:

1. The planner is failing silently and returning a response with no
   `:output :plan/id`, or
2. The plan has no tasks, or
3. Some other skip reason we haven't enumerated.

**Without diagnostic events, we can't tell which.** Every path through
`try-dag-execution` that declines to invoke the orchestrator is currently
silent. Post-mortem readers (humans and future policy checks) have no
way to know that DAG was considered and why it was skipped.

This PR adds that visibility. Implementing the actual root-cause fix
follows once we can see skip reasons in real event logs.

## What changed

### `components/workflow/src/ai/miniforge/workflow/execution.clj`

- New fn `dag-skip-reason` — pure, returns the specific reason DAG
  execution should skip (or `nil` to proceed). Enumerated reasons:
  - `:not-plan-phase` — called outside the `:plan` phase
  - `:disabled` — `:disable-dag-execution` set on ctx (sub-workflows)
  - `:no-plan-id` — phase output lacks `:plan/id`
  - `:no-tasks` — plan has `:plan/id` but empty `:plan/tasks`
- `dag-applicable?` kept as-is for backward compatibility.
- New helpers `resolve-event-stream`, `resolve-workflow-id`,
  `emit-dag-considered!` — match the lookup logic in
  `phase/telemetry.clj` so the event stream is found under any of
  `:event-stream`, `:execution/event-stream`, or
  `[:execution/opts :event-stream]`.
- `try-dag-execution` now always emits `:workflow/dag-considered`
  during the plan phase — either with `:dag/outcome :activated` +
  `:plan/id` + `:plan/task-count`, or with `:dag/outcome :skipped` +
  the specific `:dag/reason`.
- Publish errors are swallowed — observability never breaks execution.

### `components/workflow/test/ai/miniforge/workflow/dag_activation_diagnostics_test.clj`

Six unit tests covering every skip reason + activation path + the
backward-compat behavior of `dag-applicable?`. All pass
(`12 assertions, 0 failures`).

## Why this is small

This PR does not fix the root cause — it makes the root cause visible.
Once the next miniforge run emits `:workflow/dag-considered :skipped
:no-plan-id` (or `:no-tasks`), we'll know exactly which upstream path
to fix:

- `:no-plan-id` → planner's success path isn't reaching
  `(response/success plan-final ...)`; it's failing into an error
  response. Next fix is to find why the planner is returning an error.
- `:no-tasks` → planner is parsing responses into plans with empty task
  lists. Next fix is prompt/parsing.
- `:disabled` → unexpected ctx state leaking from sub-workflow context
  back up. Would indicate a bigger bug.

## Verification

```bash
$ clojure -M:dev:test -e "(require '[clojure.test :refer [run-tests]]) \
                          (require 'ai.miniforge.workflow.dag-activation-diagnostics-test) \
                          (run-tests 'ai.miniforge.workflow.dag-activation-diagnostics-test)"
Testing ai.miniforge.workflow.dag-activation-diagnostics-test

Ran 6 tests containing 12 assertions.
0 failures, 0 errors.
```

Full `bb poly test brick:workflow` blocked by the pre-existing 13
structural errors (Errors 103/106/107/108/112) that the remediation
spec is meant to fix. That's unrelated to this PR and tracked
separately.

## Follow-up

- Re-run `work/plan-from-agent-dag-wiring.spec.edn` (or any spec) via
  miniforge with this landed. Observe the `:workflow/dag-considered`
  event in `~/.miniforge/events/<workflow-id>/`. Note the `:dag/reason`.
- File the concrete fix based on observed reason. Candidates already
  spec'd at `work/plan-from-agent-dag-wiring.spec.edn` (Group 2 on
  planner prompt, Group 3 on malli validation) may or may not be the
  right lever depending on what the reason turns out to be.
- `:workflow/dag-considered` is the single new event type — add to the
  event schema component in a follow-up once consumed.
