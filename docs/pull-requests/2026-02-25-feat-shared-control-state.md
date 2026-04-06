<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat/shared-control-state — PR5a

## Layer

Infrastructure (Event Stream)

## Depends on

- None (base of TUI control stack)

## Overview

Extracts pause/resume/cancel control state into shared primitives in the
event-stream component, replacing ad-hoc atom management scattered across
the workflow runner and dashboard.

## Motivation

Both the workflow runner and TUI dashboard need to read and write
pause/resume/cancel state. Previously each created its own atoms, leading to
duplication and potential desynchronization. By extracting `create-control-state`,
`pause!`, `resume!`, `cancel!`, `paused?`, and `cancelled?` into
`event-stream/control.clj` and exporting them via the component interface,
all consumers share the same primitives and the same state.

## Changes In Detail

| File | What changed |
|------|-------------|
| `components/event-stream/src/ai/miniforge/event_stream/control.clj` | Added `create-control-state`, `pause!`, `resume!`, `cancel!`, `paused?`, `cancelled?` functions |
| `components/event-stream/src/ai/miniforge/event_stream/interface.clj` | Exported all 6 control-state functions via component interface |
| `bases/cli/src/ai/miniforge/cli/workflow_runner.clj` | Switched to `es/create-control-state` instead of inline atoms |
| `bases/cli/src/ai/miniforge/cli/workflow_runner/dashboard.clj` | Uses `requiring-resolve` calls to control-state functions |
| `components/event-stream/test/ai/miniforge/event_stream/control_test.clj` | 7 new tests covering create, pause/resume cycle, cancel, and predicate queries |

**+129 LOC** across 5 files.

## Testing Plan

- [x] 7 unit tests for control-state lifecycle
- [ ] Pre-commit hooks pass (lint, format, test, graalvm)
- [ ] Integration with PR5b (TUI control wiring) once that lands

## Deployment Plan

Refactor only — replaces internal atoms with shared primitives. No
user-visible behavior change. Merges to `main` and is consumed by PR5b.

## Related Issues / PRs

- **PR5b** (TUI control wiring) depends on this PR
- Part of the meta-loop TUI control work

## Checklist

- [x] Tests written and passing
- [x] No breaking changes to existing interfaces
- [x] Polylith interface boundaries respected
- [x] Babashka / GraalVM compatible
