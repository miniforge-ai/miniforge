<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Phase `:exit` curator interceptor slot + plan curator

## Context

Dogfood iteration 5 (workflow `59b62101`, 2026-04-19):

```text
plan     4:11  :failure  :llm-content-length 85
                         "Let me check a few specific files..."
DAG      :skipped   :no-plan-id
implement 11:09  :failure  curator: wrote no files
                          (0 tool calls in 11 minutes)
```

The malformed plan artifact (85 chars of narration, no `:plan/id`)
was allowed to flow into `:implement`, which then ran 11 minutes
of dead time before the only phase with a curator — `:implement`
via `agent/curate-implement-output` — noticed the downstream
consequence.

This PR adds curation as a first-class phase lifecycle slot so the
plan phase itself rejects the malformed artifact at its own
boundary, preventing the 11-minute downstream flail.

## What changed

### `:exit` interceptor slot

New lifecycle position in `components/workflow/.../execution.clj`,
runs after `:leave`, before transition:

```text
:enter → :leave → :exit → next phase
```

`execute-exit` is a no-op when the interceptor has no `:exit` fn
(backward compat). When present, runs as `(fn [ctx] ctx')`. Exceptions
are caught and recorded as `:type :exit-error` on
`:execution/errors` — never kills the runtime.

### `plan/curate-plan`

`components/phase-software-factory/.../plan.clj`. Validates:

- Inner `:result :status` is `:success` (not `:error` or `:failure`)
- `:output` has a `:plan/id`
- `:plan/tasks` is non-empty

Rejection codes (keyword, dispatchable by downstream tooling):

- `:curator/plan-status-not-success`
- `:curator/plan-missing-id`
- `:curator/plan-no-tasks`

Pass-through for `:already-satisfied` (planner evidence-bundle
accepted: no tasks required because the spec is already done).

`:llm-content-preview` — when available from the planner's anomaly
data — is carried through to `:phase :error :llm-content-preview` so
`mf events show` still shows what the model was doing when it
failed to plan.

### Spec

`work/phase-exit-curator-interceptor.spec.edn`. Tier `:high`,
theme `:dogfood-resilience`, axes `#{:correctness
:token-conservation :observation}`. Groups 2-4 define the
follow-up scope: move `:implement` curator to `:exit` (behavior-
preserving refactor, currently coupled to `leave-implement`'s
branch decisions), add verify/review/release curators, optional
dedicated `:phase/curator-rejected` event.

## What this does not do

- Does not move the `:implement` curator to `:exit`. Its output feeds
  `leave-implement`'s retry/terminate decision path. Refactor is
  documented as GROUP 2 in the spec.
- Does not change the planner's prompt or turn budget. Curator runs
  on whatever the planner produces.
- Does not add a retry loop. Rejected phases follow the existing
  `:error` interceptor path.
- Does not introduce any LLM-based curation. Deterministic schema
  checks only.

## Tests

- `plan_curator_test.clj` — 8 tests, 14 assertions. Accept +
  already-satisfied pass-through + each rejection code + preview
  passthrough + inner-result-status preserved on rejection.
- `phase_exit_slot_test.clj` — 5 tests, 7 assertions. Exit runs
  after leave, missing exit is no-op, exit can reject, exit
  exceptions recorded as `:exit-error`, exit works without leave.
- 21 assertions total. Pre-commit green.

## Expected behavior change

Before: plan phase `:phase/outcome :failure` → implement phase
starts → 11 min of dead time → curator catches empty diff.

After: plan phase `:phase/outcome :failure` with `:phase/error
{:curator :plan :code :curator/plan-missing-id
:llm-content-preview "..."}` → implement phase does not start
(runtime already sees :failed status). `mf events show` surfaces
the exact rejection reason.

Whether this fixes the underlying planner behavior (still emitting
narration instead of EDN) — we'll learn in the next dogfood run.
This PR makes that learning cheap.

## References

- Parent spec: `work/event-log-tool-visibility.spec.edn` (dogfood
  visibility theme).
- Adjacent spec: `work/phase-exit-curator-interceptor.spec.edn`
  (this PR's authoring record + follow-up groups).
- Observed failure: workflow `59b62101` in
  `~/.miniforge/events/`, timeline visible via
  `mf events show 59b62101-e92e-4cec-bc1b-c8243b3e2221 --no-status`.
