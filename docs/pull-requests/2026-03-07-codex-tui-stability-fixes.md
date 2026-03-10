# PR: TUI stability fixes for exit, PR freshness, and workflow detail hydration

**Branch:** `codex/tui-stability-fixes`  
**Base Branch:** `main`  
**Date:** 2026-03-07

## Summary

Fixes a cluster of TUI regressions around:

- standalone startup replaying the full historical event store,
  which made the app sluggish and effectively non-exitable while booting,
- stale PR detail state after refresh/sync,
- PR filter queries being unable to target merged vs closed state,
- workflow list hydration admitting partial event files that behave like DAG child traces,
- workflow detail panes lacking reconstructed phase / agent / artifact / gate data for persisted runs.

## Root Causes

1. **Standalone file subscription replayed all existing event files on startup**
   - The app already loads startup state from persistence.
   - Replaying every historical event file again created a massive dispatch
     backlog, duplicated workflow rows, and delayed responsiveness.

2. **Workflow startup persistence only used first/last events**
   - That was enough for a row summary, but not enough for detail panes.
   - Phase-only files were also treated as top-level workflows, which let DAG child traces leak into the flat workflow
     list.

3. **PR sync replaced list data without refreshing dependent UI state**
   - `:detail.selected-pr` stayed stale after sync.
   - Active PR filters were not recomputed after list replacement.

4. **GitHub merged PRs were normalized as `:closed`**
   - This made merged/closed filtering inaccurate even when the provider returned the correct merged information.

5. **Root update dispatch missed several translated runtime messages**
   - Agent started/completed/failed and tool/gate lifecycle messages had
     handlers but were not wired at the top-level update switch.

## Changes

### Workflow persistence and detail reconstruction

- Reworked persisted workflow loading to read full event files instead of only first/last events.
- Added detail reconstruction for:
  - phases,
  - current phase,
  - current agent,
  - streamed agent output,
  - gate validation results,
  - artifacts,
  - duration and error state.
- Excluded phase-only event files from the top-level workflow list so DAG child traces do not appear as standalone
  workflows.
- Added `load-workflow-detail` helper for direct persisted detail reconstruction.

### Workflow row and detail state

- Workflow rows now keep a `:detail-snapshot`.
- Live event handlers update that snapshot so detail views remain useful without replaying full history.
- Entering workflow detail now uses the row snapshot instead of a shallow placeholder.
- Workflow-added is now an upsert instead of blindly appending duplicate rows.

### Standalone file subscription

- Added `:hydrate-existing?` behavior to file tracking.
- Standalone TUI now tracks existing files from EOF instead of replaying all historical events at startup.
- Newly discovered files still hydrate from the beginning, so active runs remain observable.

### PR sync and filtering

- PR sync now refreshes the active PR detail row when the selected PR still exists in the newly synced data.
- Active PR filters are recomputed after sync.
- Added `state:` / `status:` filter qualifiers for the PR filter palette.

### Provider status normalization

- GitHub PR fetch now requests `mergedAt`.
- Normalization now maps merged PRs to `:merged` instead of collapsing them into `:closed`.

### Update wiring

- Added root update dispatch for:
  - `:msg/agent-started`
  - `:msg/agent-completed`
  - `:msg/agent-failed`
  - `:msg/gate-started`
  - `:msg/tool-invoked`
  - `:msg/tool-completed`

## Testing

- Focused component tests:
  - `XDG_CONFIG_HOME=/tmp/clj-config clojure -M:test -e "(require ...)"`
  - Result: `98 tests, 521 assertions, 0 failures, 0 errors`
- Project persistence tests:
  - `XDG_CONFIG_HOME=/tmp/clj-config clojure -e "(require 'ai.miniforge.tui-views.persistence-test) ..."`
  - Result: `14 tests, 39 assertions, 0 failures, 0 errors`
- Packaging:
  - `bb build:tui`
  - Result: built `dist/miniforge-tui` successfully

## Notes

- This change intentionally avoids replaying the entire historical event store during standalone startup.
- Persisted workflow detail is reconstructed from disk on load, and live
  event handlers now keep row snapshots current during active sessions.
