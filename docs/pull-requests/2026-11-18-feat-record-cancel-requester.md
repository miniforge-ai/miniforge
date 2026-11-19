<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(event-stream): record who cancelled a run and build workflow/cancelled

## Overview

A cancel now records who asked for it and from where, and the event stream can build the
`:workflow/cancelled` event N3 defines. Nothing publishes it yet: the follow-up PR makes the
runner end a cancelled run as `:cancelled` and publish this event instead of `:workflow/failed`.

## Motivation

1. N3 says a cancelled workflow is a terminal state distinct from `:workflow/failed`, reported
   by `:workflow/cancelled` alone, and that consumers counting failures must not count
   cancellations. N2 lists `:cancelled` as a terminal state.
2. Today an operator cancel ends the run `:failed`. The issue 2377 acceptance rerun on c93b2df43
   showed it: the cancel was applied and verified, the agent's process tree was stopped, and the
   run still ended `Workflow failed: Workflow stopped by control plane`, with a critical attention
   event.
3. `:workflow/cancelled` requires `:cancel/requested-by` and `:cancel/source`. The control state
   only held `:stopped true`, so the runner had no way to say who cancelled. The event had a
   draft schema and consumers (the supervisory accumulator, the TUI, retention and cleanup) but
   no constructor.

## Changes in Detail

1. `es/cancel!` gains a 2-arity that also records the cancel's `:cancel/requested-by`,
   `:cancel/source`, and when given `:cancel/reason` and `:action/id`. `es/cancellation` reads it
   back, and returns nil for a run that is not cancelled.
2. `es/workflow-cancelled` builds the event from that record and the run's duration. On the
   current publication path it is the `:workflow/cancelled` draft. On the legacy path it is a
   `:workflow/cancelled` envelope too, since N3 never folds a cancel into `:workflow/completed`.
   It is registered in `event-type-registry.edn` (no browser handler yet) and is `:public`, like
   the other terminal events.
3. The operator's cancel records the intervention's requester, reason (its justification) and
   source. N3 allows `:cli`, `:api`, `:tui`, `:supervisor` and `:control-action`, so the
   intervention sources map onto those:
   1. `:cli`, `:tui` and `:api` keep their names;
   2. `:dashboard` reports `:api`, since it posts over the HTTP API;
   3. `:native-app` reports `:tui`, a local operator UI like the TUI;
   4. `:meta-agent` reports `:supervisor`;
   5. any other source reports `:api`.

## Testing Plan

1. `workflow_terminal_constructor_test.clj`: the cancelled event commits and replays on the
   current path. It is refused without a requester, with `:control-action` and no `:action/id`,
   and with a source N3 does not name. On the legacy path it is a `:workflow/cancelled` with no
   `:workflow/status`.
2. `application_test.clj`: an applied cancel intervention records its requester and source on
   the runner's control state, and each intervention source maps to the N3 source above.
3. The event-stream registry, control, draft and interface tests and the operator application
   tests pass: 95 tests, 1237 assertions.

## Notes

1. The source mapping loses the difference between a dashboard and an API cancel, and between
   the native app and the TUI. Each intervention's own lifecycle events keep its exact source.
   Naming `:dashboard`, `:native-app` and `:meta-agent` in N3 is a normative change for the
   product owner to decide.
2. The control-plane adapter's `send-command` still cancels with no record. It has no
   production caller.

## Deployment Plan

No configuration or data migration. The event is not published until the follow-up PR.

## Related Issues/PRs

1. miniforge-ai/miniforge#2377: the cancel acceptance issue, whose rerun found the `:failed`
   disposition.
2. N3 `workflow/cancelled` and N2 §2.2 terminal states.

## Checklist

1. [x] Regression tests fail on the previous code
2. [ ] Independent review and pinned-standards check
3. [ ] Copilot threads answered and resolved, CI green, no conflicts
4. [ ] Merged
