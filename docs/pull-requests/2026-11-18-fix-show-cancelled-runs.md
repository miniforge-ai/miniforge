<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(web-dashboard): show a cancelled run as cancelled

## Overview

The web dashboard, its archive, the event timeline and the TUI's DAG kanban now read a run that
ended with `:workflow/cancelled` as cancelled and finished. Two of the files involved were over
the 3-layer limit, so the PR first splits them, moving code without changing it.

## Motivation

1. N3 reports a cancel with `:workflow/cancelled` alone. The cancel work in progress (#2546)
   makes every operator cancel end that way instead of with `:workflow/failed`.
2. These readers looked only for completed and failed terminal events. A cancelled run would show
   as running and then stale in the dashboard, unfinished in the archive, with no terminal marker
   in the timeline, and in no column of the TUI kanban. The archive also mapped an older
   completed-with-status-cancelled event to `:failed`.
3. `archive.clj` (4 layers) and `state/workflows.clj` (8 layers) were over the 3-layer limit
   (rule 210), and the pre-commit stratum check blocks any edit to them until they are split.

## Changes in Detail

1. Movement-only splits:
   1. `archive.clj`: the pure summary functions move to `archive-summary`.
   2. `state/workflows.clj`: event helpers, dependency health, the live and disk sources, and
      the summaries move to `state.workflows.events`, `.dependencies`, `.sources` and
      `.summaries`; `state.workflows` keeps the event filters and `get-workflows` /
      `get-workflow-detail`. `events-dir-path` moves to `sources` with the disk read, so the three
      tests that redefine it point at `sources` (only their alias and var paths change). Four
      functions become public because their callers now sit in another namespace.
2. Cancelled runs:
   1. `wf-status` returns `:cancelled` for a `:workflow/cancelled` event, or an older completed
      event with status `:cancelled`; `:cancelled` is a terminal status, so progress shows 100.
   2. The archive summary reads the same, and counts `:workflow/cancelled` as terminal.
   3. The archived view labels it "Cancelled", and the stylesheet gives the cancelled status
      dot, badge and summary item the warning colour.
   4. The fleet evidence page keeps a cancelled run, as it keeps completed and failed ones.
   5. The event timeline counts `:workflow/cancelled` as a terminal event.
   6. The TUI DAG kanban shows a cancelled workflow as a done card.

## Testing Plan

1. Both splits leave every test of the code they move passing, with the same counts before and
   after: the archive tests, and `state.workflows-test`, `sink-layout-test`,
   `server.request-guard-test` and `state.fleet-test` (49 tests).
2. New tests: `archive_summary_test` (status and summary of a cancelled run),
   `get-workflows-shows-a-cancelled-run-as-cancelled-test`, a cancelled timeline event, and
   `dag_kanban_test`.
3. The dashboard, archive, fleet, timeline and TUI test namespaces pass: 224 tests,
   1072 assertions.

## Notes

1. The automation-edge correlator does not handle `:workflow/cancelled` yet: a routing edge whose
   handler is cancelled stays pending. N5-delta-4 §2.4 defines no cancel transition for an edge,
   and its `core.clj` has layer headings without stratum metadata at 4 layers, so it needs a spec
   rule and a mechanical split first.
2. Other TUI readers still do not know `:cancelled`: a cancelled workflow's detail view shows its
   phase as running, `:archive all-done` skips it, and the list and kanban draw it with the
   pending glyph. The TUI's own cancel already reaches them today. `navigation.clj`,
   `command.clj` and `workflow_list.clj` are over the 3-layer limit and `widget/status.clj` has no
   stratum metadata, so each needs a mechanical change first.
3. The browser shows no toast for `workflow/cancelled` yet, while completed and failed get one.
4. The runner change, miniforge-ai/miniforge#2546, merges after this PR.

## Deployment Plan

No configuration or data migration.

## Related Issues/PRs

1. miniforge-ai/miniforge#2546: a confirmed cancel ends the run cancelled; this PR merges first.
2. miniforge-ai/miniforge#2544 and miniforge-ai/miniforge#2545: the rest of the cancel wave.
3. miniforge-ai/miniforge#2377: the cancel acceptance issue.

## Checklist

1. [x] Regression tests fail on the previous code
2. [ ] Independent review and pinned-standards check
3. [ ] Copilot threads answered and resolved, CI green, no conflicts
4. [ ] Merged
