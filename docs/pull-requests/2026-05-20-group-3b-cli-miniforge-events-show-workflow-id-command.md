<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# GROUP 3b: CLI `miniforge events show <workflow-id>` command

**PR:** [#941](https://github.com/miniforge-ai/miniforge/pull/941)
**Branch:** `mf/group-3b-cli-miniforge-events-show-workf-d06f1098`

## Summary

GROUP 3b: CLI `miniforge events show <workflow-id>` command.

## Files Changed

- `bases/cli/src/ai/miniforge/cli/main.clj` (modify) — register `events show` subcommand
- `bases/cli/src/ai/miniforge/cli/main/commands/events.clj` (create) — `events-show` core + CLI handler; delegates to
  `event-stream.interface/render-timeline`
- `bases/cli/test/ai/miniforge/cli/main/commands/events_test.clj` (create) — unit + integration tests
- `components/event-stream/src/ai/miniforge/event_stream/interface.clj` (modify) — expose `read-workflow-events-by-id` +
  `render-timeline`
- `components/event-stream/src/ai/miniforge/event_stream/timeline.clj` (create) — renderer (shared from #940)
- `components/event-stream/test/ai/miniforge/event_stream/timeline_test.clj` (create) — renderer tests (shared from
  #940)

## Test Results

_No test artifacts available._

## Review Decision

_No review artifacts available._
