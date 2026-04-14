<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# PR: Wire TUI pause/resume/cancel commands to filesystem control

**Branch:** `feat/tui-control-wiring`
**Date:** 2026-02-25
**Spec:** PR5b — TUI control wiring

## Summary

Wires TUI `:pause`, `:resume`, and `:cancel` commands to the filesystem-based
control protocol. When a user issues one of these commands, the TUI writes a
command file to `~/.miniforge/commands/<workflow-id>/` — the same directory the
CLI workflow runner's dashboard watches.

This builds on PR5a which added `create-control-state`, `pause!`, `resume!`,
and `cancel!` to `event-stream/control.clj`.

## Changes

### `components/tui-views/src/ai/miniforge/tui_views/effect.clj`

- Added `control-action` effect constructor returning `{:type :control-action :action _ :workflow-id _}`

### `components/tui-views/src/ai/miniforge/tui_views/update/command.clj`

- Added `selected-workflow-id` helper to resolve a single workflow from the current selection
- Added `cmd-pause` and `cmd-resume` command handlers that emit `:control-action` side-effects
- Registered `:pause` and `:resume` in the command table

### `components/tui-views/src/ai/miniforge/tui_views/interface.clj`

- Added `clojure.java.io` require
- Added `handle-control-action` — writes `{:command <action> :timestamp <Date>}` EDN to the commands directory
- Wired `:control-action` case in `dispatch-effect`

### `components/tui-views/test/ai/miniforge/tui_views/command_test.clj`

- `control-action-effect-test` — verifies effect map shape for pause/resume/cancel
- `pause-command-test` — verifies `:pause` command produces correct side-effect or error flash
- `resume-command-test` — verifies `:resume` command produces correct side-effect or error flash

## Protocol

Command files are written to:

```
~/.miniforge/commands/<workflow-id>/<timestamp>.edn
```

Each file contains:

```edn
{:command :pause :timestamp #inst "..."}
```

The CLI dashboard runner polls this directory and dispatches to
`event-stream/control` functions.

## Testing

- Unit tests cover effect shape and command handler wiring
- Manual: start TUI, select a workflow, run `:pause` / `:resume` and verify files appear
