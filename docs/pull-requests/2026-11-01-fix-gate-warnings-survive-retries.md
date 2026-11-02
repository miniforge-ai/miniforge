<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: Gate warnings survive on the phase result and in gate history

## Overview

Gate warnings used to be thrown away. They are now recorded on the phase
result as `:phase/gate-warnings`, on both allow and deny, and written to
the append-only `gate-history.edn`. A warning raised on a phase that is
later re-entered stays on record.

## Motivation

`apply-gate-validation` in `workflow/execution_transition.clj` ran the
phase's gates through `gate/check-gates` and kept only two things: the
decision envelope, and on a deny the failed gates' `[:gate :errors]` as
`:phase/gate-failures`. Every gate's `:warnings` were discarded.

A warning never changes the decision, so the envelope has nowhere to put
it, and nothing else picked it up. Warnings therefore never reached
`:execution/phase-results`, the phase checkpoint, or the gate history.
The case that matters is `:codex-pin-not-fetched` from the
`:codex-consultation` gate (Codex SPEC §7.4.3, verdict 3). That gate
passes with this warning when the agent was given its pinned landings but
never fetched them with `context_read`. When a phase is re-entered, by a
redirect or a repair, its checkpoint is overwritten. That leaves the
history as the only record, and the history had no warnings.

## Changes in Detail

- `workflow/execution_transition.clj`: a new Layer 0 `gate-warnings`
  returns one `{:gate kw :warnings [...]}` per gate that warned, in gate
  order, or nil when none did. It uses the same shape as
  `:phase/gate-failures`. `apply-gate-validation` sets
  `:phase/gate-warnings` from it on both branches, through one clause in
  its outer `cond->`. The key is set only when some gate warned.
- `workflow/checkpoint_store.clj`: `append-gate-history!` writes
  `:phase/gate-warnings` next to `:phase/gate-errors` and
  `:phase/gate-failures`, using the phase result's own key name.

## Testing Plan

- `phase_transitions_test.clj`:
  - `gate-warnings-test`: ordering, list-to-vector coercion, and nil when
    no gate warned.
  - `apply-gate-validation-keeps-gate-warnings`: a passing
    `:codex-consultation` phase with an unread pin keeps the warning; a
    failing phase keeps it beside `:phase/gate-failures`; a phase with no
    warnings gets no key.
- `checkpoint_store_test.clj`: the re-entry history test also checks that
  the allowed iteration's warning is in the history.
- Locally, only the Clojure 1.12 reader and a standalone evaluation of
  `gate-warnings` ran, because Clojars is unreachable from the authoring
  environment. CI ran the full suite.

## Deployment Plan

No configuration or data migration. Phase results and gate-history
entries gain `:phase/gate-warnings` when a gate warns. Readers that
ignore the key are unaffected.

## Related Issues/PRs

- #2311: makes a gate decision count only once a durable sink has written
  it. It merged before this PR. This PR merged as `d83c1b6` without CI
  re-running against #2311. Its pass-path test cases streamed to
  `{:sinks []}`, so they failed on main.
- #2319: fixed that red main by giving those cases a durable-sink ctx.
- #2316: trimmed this PR's `gate-warnings` docstring and clause comment
  to the dewey 600 limits.

## Checklist

- [x] Behavior and tests in the same PR (dewey 716)
- [x] CI green on the merged head
- [x] Copilot findings fixed and resolved
- [ ] CI re-run against main before merge. It was not, which caused the
      breakage that #2319 fixed.
