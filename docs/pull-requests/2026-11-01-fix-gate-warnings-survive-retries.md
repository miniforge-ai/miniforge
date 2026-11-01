<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix(workflow): gate warnings survive on the phase result and in gate history

**Tier:** observability / Codex §7.4.3 follow-up
**Theme:** gates, checkpoints, Thesium Codex consultation

## Problem

`apply-gate-validation` in `workflow/execution_transition.clj` runs the
phase's gates through `gate/check-gates` and then keeps only two things from
the result: the decision envelope, and on a deny the failed gates'
`[:gate :errors]` as `:phase/gate-failures`. Every gate's `:warnings` were
thrown away.

A warning never changes the decision, so the envelope has nowhere to put it.
Nothing else picked it up either, so a warning never reached
`:execution/phase-results`, the phase checkpoint, or the append-only
`gate-history.edn`. The case that matters here is `:codex-pin-not-fetched`
from the `:codex-consultation` gate (Codex SPEC §7.4.3 verdict 3). That
gate passes with the warning when the agent was given its pinned landings
but never fetched them with `context_read`. The warning was raised and
dropped on every iteration. When a phase is re-entered (a redirect, a
repair) its checkpoint is overwritten, so the history is the only record
left, and it had no warnings in it.

## Changes

### `components/workflow/src/ai/miniforge/workflow/execution_transition.clj`

- New Layer 0 `gate-warnings`: from a `check-gates` result it returns one
  `{:gate kw :warnings [...]}` for each gate that raised a warning, in gate
  order, or nil when no gate did. This is the same shape as
  `:phase/gate-failures`.
- `apply-gate-validation` puts that value on the phase result as
  `:phase/gate-warnings`, on both the allow and the deny branch. The key is
  added only when some gate warned, so the phase result for a phase with no
  warnings is unchanged.

### `components/workflow/src/ai/miniforge/workflow/checkpoint_store.clj`

- `append-gate-history!` writes `:phase/gate-warnings` next to
  `:phase/gate-errors` and `:phase/gate-failures`. It uses the phase
  result's own key name, so the history and the checkpoint name the datum
  the same way.

## Testing

- `phase_transitions_test.clj`:
  - `gate-warnings-test`: covers ordering, a list of warnings coerced to a
    vector, and nil when there are no warnings.
  - `apply-gate-validation-keeps-gate-warnings`: a passing
    `:codex-consultation` phase with an unread pin keeps the warning. A
    failing phase (`:review-approved` denies) keeps the warning alongside
    `:phase/gate-failures`. A phase with no warnings gets no key.
- `checkpoint_store_test.clj`: `gate-history-survives-phase-re-entry-test`
  now also checks that the allowed iteration's warning is in the history.
- **`bb test` was NOT run.** The authoring environment has no Clojure CLI or
  Babashka, and `repo.clojars.org` returns 403 through its proxy. What was
  checked: all four edited files read cleanly with the Clojure 1.12 reader,
  and `gate-warnings` was evaluated on its own against the test inputs and
  returned the expected values. The full suite still has to run in CI or on
  review.
