<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(tui): Add file-watcher subscription for standalone `mf tui` command

**Branch**: `feat/tui-file-watcher`
**Date**: 2026-02-25
**Status**: Open

## Overview

Add a file-based event subscription that tail-follows `~/.miniforge/events/*.edn` files, enabling the TUI to run as a
standalone process monitoring running workflows without an in-memory event stream.

## Motivation

The existing TUI (`start-tui!`) requires an in-memory event stream, meaning it must run in the same process as the
workflow executor. This limits the TUI to embedded mode only. With file-based subscription, `mf tui` can launch
independently and discover/monitor any running workflows via the filesystem protocol already used by
`event-stream/file-sink`.

## Changes in Detail

### New Files

| File | Purpose |
|------|---------|
| `components/tui-views/src/ai/miniforge/tui_views/file_subscription.clj` | File-based event subscription: tail-follows event files, parses EDN, dispatches to TUI |
| `bases/cli/src/ai/miniforge/cli/main/commands/tui.clj` | Standalone `tui-cmd` using `requiring-resolve` for cross-component call |

### Modified Files

| File | Changes |
|------|---------|
| `components/tui-views/src/ai/miniforge/tui_views/subscription.clj` | Made `translate-event` public (was `defn-`) for reuse by file subscription |
| `components/tui-views/src/ai/miniforge/tui_views/interface.clj` | Added `start-standalone-tui!` entry point, added `file-subscription` require |
| `bases/cli/src/ai/miniforge/cli/main/commands/monitoring.clj` | Updated `tui-cmd` to use `start-standalone-tui!` via `requiring-resolve` |

## Architecture

```text
mf tui  -->  monitoring/tui-cmd  -->  interface/start-standalone-tui!
                                          |
                                          v
                                  file-subscription/subscribe-to-files!
                                          |
                                          v
                              ~/.miniforge/events/*.edn  <-- file-sink (from mf run)
```

- **Events out**: `mf run` writes to `~/.miniforge/events/<workflow-id>.edn` via file sink
- **Events in**: `file-subscription` uses `RandomAccessFile` with position tracking to tail-follow
- **Commands out**: `send-command!` writes `.edn` to `~/.miniforge/commands/<workflow-id>/`
- Polling: 500ms for new lines, 2s for new files (configurable)

## Key Design Decisions

1. **RandomAccessFile for tail-following**: Efficient seek-based reading avoids re-reading entire files
2. **Position tracking via atoms**: Each tracked file has its own position atom for independent progress
3. **Reuse `translate-event`**: Made public so both in-memory and file-based subscriptions share the same
  event-to-message translation
4. **Daemon thread**: Background poller is a daemon thread that dies with the JVM
5. **Hydration on startup**: Existing file content is read in full to reconstruct current state

## Testing Plan

- [ ] `bb test`
- [ ] Run `mf run ...` to emit filesystem events and verify standalone `mf tui`
  follows them live
- [ ] Verify command writes still flow through `~/.miniforge/commands/`
