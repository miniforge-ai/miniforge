<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Fix phase outcome + surface result error + checkpoint/resume spec

## Context

PR #571 added `:workflow/dag-considered` events. PR #573 enriched them with
a keys-only `:dag/diagnostic`. Today's re-run surfaced the actual cause:

```
:dag/outcome   :skipped
:dag/reason    :no-plan-id
:dag/diagnostic
  :result/status :error
  :result/keys   [:error :metrics :status]
  :output/type   :nil
```

**Two distinct bugs exposed:**

1. The planner is returning `:status :error` with no `:output`. But —
2. The phase-completed event reports `:phase/outcome :success` over
   that `:error` inner result. Silent failure.

Tracing the silent-failure bug: `runner_events.clj:73` computes
`succeeded?` from `phase/succeeded-or-done?` which reads the OUTER
phase map's `:status` (set to `:completed` by `leave-plan` — meaning
the interceptor ran without crashing). It never looks at the inner
`:result :status`. So an agent that returns a failure response while
its wrapper completes cleanly is reported as a successful phase.

## What changed

### 1. `components/workflow/.../runner_events.clj` — phase outcome propagation

- New helper `inner-result-failed?` — true when `:result :status` is in
  `#{:error :failed :failure}` or `:result :success?` is false (matches
  both agent-response and DAG-result shapes).
- `build-phase-event-data` now requires BOTH the outer phase and the
  inner result to succeed before reporting `:outcome :success`. Either
  fails → `:outcome :failure`.
- When the phase reports failure, `:error` is pulled from the phase's
  `:error` key OR the inner `:result :error` key, so the event carries
  the actual error message.

This is a **regression fix** that affects every phase, not just plan.
Verify / review / release phases that surface inner-result failures will
now correctly report them upward to the dashboard, TUI, and control
plane. N3 event contracts unchanged — only the `:phase/outcome` values
become more accurate.

### 2. `components/workflow/.../execution.clj` — surface result error in DAG diagnostic

`dag-skip-diagnostic` now attaches a `:result/error` submap (bounded,
keys-only where it matters) when `:result/status` is `:error`, `:failed`,
or `:failure`. Fields captured:

- `:error/message` (truncated to 500 chars)
- `:error/category` (if keyword)
- `:anomaly` (if keyword)
- `:error/data-keys` (top-level keys of `:error/data`, no values)

Keys-only for the same reason as the broader diagnostic — full error
data can include large prompts or stack traces. The truncated message
+ anomaly keyword is enough to diagnose the source.

### 3. Tests

- `dag_activation_diagnostics_test.clj` — 4 new tests for `:result/error`
  surfacing (with data-keys, anomaly preservation, 500-char truncation,
  absence on success). File now at 15 tests / 40 assertions.
- `phase_outcome_from_inner_result_test.clj` — 6 new regression tests
  covering every inner-status case (`:error`, `:failed`, `:failure`,
  `:success? false`, true-success, plus an end-to-end event-emission
  assertion).

All 21 tests in the two files pass, 42 assertions, 0 failures.

### 4. `work/workflow-phase-checkpoint-and-resume.spec.edn`

Work spec for phase-granularity checkpoint + resume. 8 groups: per-phase
`.edn` writes, `miniforge resume <id>`, spec-hash integrity, explicit
`--from-phase` override, `miniforge list --resumable`, GC, cross-workflow
replay (nice-to-have), worktree-persistence integration.

### 5. `specs/normative/N2-delta-phase-checkpoint-and-resume.md`

Normative extension to N2. N2 §1.1 already states "Workflows MUST be
resumable from last successful phase" as a design principle — this delta
operationalizes it with 10 requirements: atomic per-phase writes,
workflow manifest with spec hash + completed phases, resume semantics,
retention + GC policy, event emissions, worktree integration, explicit
billing/replay semantics (completed phases MUST NOT re-execute on
resume).

Why now: three failure modes — provider rate limits, network outages,
and LLM-provider bugs — all recur constantly in real usage. Burning
tokens on already-completed phases after any of these is a product
failure, not just a dogfood nit.

## Verification

```bash
$ clojure -M:dev:test -e "(require '[clojure.test :refer [run-tests]]) \
                          (require 'ai.miniforge.workflow.dag-activation-diagnostics-test :reload) \
                          (require 'ai.miniforge.workflow.phase-outcome-from-inner-result-test :reload) \
                          (run-tests 'ai.miniforge.workflow.dag-activation-diagnostics-test \
                                     'ai.miniforge.workflow.phase-outcome-from-inner-result-test)"
Ran 21 tests containing 42 assertions.
0 failures, 0 errors.
```

## Expected behavior on next run

After this merges, re-running `work/plan-from-agent-dag-wiring.spec.edn`
should produce:

1. `:phase/outcome :failure` on the plan phase event (since the inner
   result IS an error)
2. `:dag/diagnostic :result/error` populated with the actual error
   message — at last, a definitive pointer to WHY the planner is
   failing on this spec
3. Workflow lifecycle downstream can decide to fail or retry instead of
   proceeding to implement with no plan

## Follow-up

- The planner's root-cause error is still undiagnosed. The enriched
  diagnostic from this PR will point at it on the next run. Fix lands
  in the next PR, targeted not speculative.
- Checkpoint/resume implementation is not in this PR. Specs land here;
  implementation follows per the work spec.
