<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(workflow): Add chain lifecycle event emission

**Branch**: `feat/chain-events`
**Date**: 2026-02-25
**Status**: Open

## Overview

Add chain lifecycle event emission to `run-chain` so the TUI and other subscribers can observe chain execution progress in real time. This builds on PR1b's chain executor and the existing event-stream infrastructure.

## Motivation

The chain executor currently runs silently with no observability. For the TUI to display chain progress (which step is running, success/failure status), the chain needs to emit events through the event-stream. This follows the same pattern used by workflow, agent, gate, and tool lifecycle events.

## Changes in Detail

### New Files

| File | Purpose |
|------|---------|
| `components/workflow/test/ai/miniforge/workflow/chain_events_test.clj` | Tests for chain event emission in success, failure, and no-stream cases |

### Modified Files

| File | Changes |
|------|---------|
| `components/event-stream/src/ai/miniforge/event_stream/core.clj` | Added 6 chain event constructors (Layer 4) |
| `components/event-stream/src/ai/miniforge/event_stream/interface.clj` | Added Layer 2j with delegating wrappers for chain events |
| `components/workflow/src/ai/miniforge/workflow/chain.clj` | Added `emit!` helper and event emission throughout `run-chain` |

## Architecture

### Event Types

| Event | When Emitted |
|-------|-------------|
| `:chain/started` | Before the execution loop begins |
| `:chain/step-started` | Before each step executes |
| `:chain/step-completed` | After a step succeeds |
| `:chain/step-failed` | When a step fails |
| `:chain/completed` | After all steps complete successfully |
| `:chain/failed` | When the chain stops due to a step failure |

### Cross-Component Resolution

The `emit!` helper uses `requiring-resolve` to call event-stream functions from the workflow component, consistent with the codebase pattern for cross-component calls under Babashka compatibility constraints.

### Backward Compatibility

When no `:event-stream` key is present in `opts`, `emit!` is a no-op. Existing callers of `run-chain` are unaffected.

## Testing

- Success path: 2-step chain emits `started, step-started, step-completed, step-started, step-completed, completed`
- Failure path: failing step emits `started, step-started, step-failed, failed`
- No event-stream: chain runs silently without errors
