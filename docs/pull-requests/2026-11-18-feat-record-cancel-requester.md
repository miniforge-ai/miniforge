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
3. `:workflow/cancelled` requires `:cancel/requested-by` and `:cancel/source`, and N3 requires
   `:cancel/source :control-action` with the action's `:action/id` for a cancel that arrives
   through the N8 control interface. The control state only held `:stopped true`. The event had
   a draft schema and consumers (the supervisory accumulator, the TUI, retention and cleanup)
   but no constructor.

## Changes in Detail

1. `es/cancel!` gains a 2-arity that also records the cancel's `:cancel/requested-by`,
   `:cancel/source` and, for a control action, `:action/id`. The first record is kept: the run
   stops on the first cancel, so a second one does not rename it. `es/cancellation` reads it.
2. `es/workflow-cancelled` builds the event from that record and the run's duration, as a
   `:workflow/cancelled` on both publication paths, since N3 never folds a cancel into
   `:workflow/completed`. It returns an `:invalid-input` anomaly for a record N3 does not accept:
   a blank or missing requester, a source that is not a keyword, a `:control-action` source with
   no action id, or a duration that is not a non-negative integer. It is registered in
   `event-type-registry.edn` with no browser handler yet, and is `:public` like the other
   terminal events.
3. The operator's cancel records the intervention's requester (as a string) and its source. A
   cancel carrying out an N8 control action reports `:control-action` and the action's id. N3
   allows `:cli`, `:api`, `:tui`, `:supervisor` and `:control-action`, so the other intervention
   sources map onto those:
   1. `:cli`, `:tui` and `:api` keep their names;
   2. `:dashboard` reports `:api`, since it posts over the HTTP API;
   3. `:native-app` reports `:tui`, a local operator UI like the TUI;
   4. `:meta-agent` reports `:supervisor`;
   5. any other source reports `:api`.
4. The dashboard's structured control path puts the control action's id into the intervention's
   details, so the operator can report it.

## Testing Plan

1. `workflow_terminal_constructor_test.clj`:
   1. the cancelled event, from the CLI and from a control action, commits and replays on the
      current path, and on the legacy path is a `:workflow/cancelled` with no `:workflow/status`;
   2. each record N3 does not accept is refused on both paths, and a source N3 does not name is
      refused at publication;
   3. a second cancel keeps the first record, and a cancel with none leaves none.
2. `application_test.clj`: an applied cancel intervention records its requester and source on
   the runner's control state, each intervention source maps to the N3 source above, a control
   action's id (as a uuid or a string) gives `:control-action`, and a non-string requester is
   recorded as a string.
3. `control_evidence_test.clj`: each dashboard control intervention names the control action it
   carries out.
4. The event-stream registry, control, draft and interface tests, the operator application tests
   and the dashboard control tests pass: 107 tests, 1351 assertions.

## Notes

1. The event carries the requester, an identifier, which N3 §8.4 classes as public. It does not
   carry the intervention's justification: N3 §8.4 classes free text as payload, which must be
   suppressible per field, and this stream suppresses whole events only. The justification stays
   on the intervention's own events, which are confidential.
2. The source mapping loses the difference between a dashboard and an API cancel, and between
   the native app and the TUI. Each intervention's own lifecycle events keep its exact source.
   Naming `:dashboard`, `:native-app` and `:meta-agent` in N3 is a normative change for the
   product owner to decide.
3. The control-plane adapter's `send-command` still cancels with no record. It has no
   production caller. The follow-up PR gives such a cancel an explicit unrecorded requester.
4. N3's optional `:workflow/phase` is not carried.

## Deployment Plan

No configuration or data migration. The event is not published until the follow-up PR.

## Related Issues/PRs

1. miniforge-ai/miniforge#2377: the cancel acceptance issue, whose rerun found the `:failed`
   disposition.
2. N3 `workflow/cancelled`, N3 §8.4 field classes, and N2 §2.2 terminal states.

## Checklist

1. [x] Regression tests fail on the previous code
2. [x] Independent review and pinned-standards check
3. [ ] Copilot threads answered and resolved, CI green, no conflicts
4. [ ] Merged
