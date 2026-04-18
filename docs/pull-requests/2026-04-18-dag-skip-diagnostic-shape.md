<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# DAG skip diagnostic — include phase-result shape when :no-plan-id fires

## Context

PR #571 added `:workflow/dag-considered` events so every skip of the DAG
orchestrator is observable. The first instrumented run confirmed the
skip fires with `:dag/reason :no-plan-id` — meaning `[:phase :result :output]`
lacks `:plan/id` at DAG check time.

That's still too coarse to act on. The `:no-plan-id` skip can happen
because:

1. Planner threw → `plan-from-agent` caught → `(response/failure e)`
   returned → `:result {:status :failure :error ...}`, no `:output`
2. Planner streamed → parsed LLM text into some `:output` shape that
   doesn't have `:plan/id`
3. Planner streamed → `:output` is `nil`
4. Something reshapes the result between `enter-plan` and
   `extract-phase-result`

Tracing the call path by inspection (`plan.clj` → `agent/interface/invoke`
→ `runtime/invoke` → `agent-proto/invoke` → FunctionalAgent's protocol
impl which swaps args for `invoke-fn`) confirmed the planner does
receive correct `(ctx, task)` args — no arg-inversion bug there. So the
bug lives somewhere else in the pipe.

This PR adds one more diagnostic layer: when `:no-plan-id` / `:no-tasks`
fires, the event carries a keys-only snapshot of what the plan phase
returned. Next run tells us which of the four cases above is happening.

## What changed

### `components/workflow/.../execution.clj`

- New fn `dag-skip-diagnostic` — builds a keys-only structured snapshot
  of `phase-result`. Top-level keys of `phase-result` and `:result`, the
  `:result/status`, the `:output/type` (one of `:nil :map :sequential
  :string :other`), output keys if it's a map, `:output/has-plan-id?`,
  and `:plan/task-count` for the `:no-tasks` case.
- New helper `classify-output` — returns a shape-type keyword without
  leaking content.
- `try-dag-execution` now attaches `:dag/diagnostic` to the
  `:workflow/dag-considered` event when the skip reason is
  `:no-plan-id` or `:no-tasks`. Other skip reasons (non-plan phase,
  `:disable-dag-execution`) don't include the diagnostic since their
  cause is self-evident.

### `components/workflow/test/.../dag_activation_diagnostics_test.clj`

- 5 new unit tests covering every observable case: success-with-wrong-
  shape output, `:nil` output, `:failure` status, `:no-tasks` + task-
  count, and **bounded-no-leakage** (verifies full task content doesn't
  escape into the diagnostic).
- All 11 tests pass (27 assertions, 0 failures).

## Why keys-only

Values of `:output` can include the full LLM-generated plan (100+ tasks
with descriptions, etc.). Putting that in every event would bloat logs.
Keys + types are enough to diagnose which code path produced the shape;
the full content can be retrieved from the artifact store or the run's
tmp dir when needed.

## Verification

```bash
$ clojure -M:dev:test -e "(require '[clojure.test :refer [run-tests]]) \
                          (require 'ai.miniforge.workflow.dag-activation-diagnostics-test :reload) \
                          (run-tests 'ai.miniforge.workflow.dag-activation-diagnostics-test)"
Ran 11 tests containing 27 assertions.
0 failures, 0 errors.
```

## Follow-up

- Re-run `work/plan-from-agent-dag-wiring.spec.edn` (or any spec) with
  this landed. Observe the `:dag/diagnostic` in `:workflow/dag-considered`.
- The four possible cases above each point to a different fix. Next PR
  will be targeted, not speculative.

## Broader principle

`specs/normative/N3-event-stream.md` governs what counts as a legitimate
diagnostic event. This PR opts for structured keys-only payloads that
are meaningful at any log level — including trace/debug — rather than
free-form strings. Future phases/stream failures should follow the same
pattern so flipping log level surfaces useful information, not noise.
