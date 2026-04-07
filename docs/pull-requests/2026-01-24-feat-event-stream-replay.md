# PR8: Event Stream Replay for Reproducibility

**Branch:** `feat/event-stream-replay`  
**Date:** 2026-01-24  
**Layer:** Infrastructure (Persistence + Replay)  
**Part of:** N1 Architecture Completion (PR 8 of 8)  
**Depends On:** None (independent)  
**Blocks:** None

## Overview

Implements event stream replay capability to enable workflow state reconstruction from events.
This is essential for reproducibility (N1 §5.2) and enables time-travel debugging, audit replay,
and determinism verification.

## Motivation

**N1 Spec Requirement (§5.2.1):**

The spec requires that workflows be reproducible - same inputs produce same outputs. Event stream
replay is the mechanism that enables this by allowing state reconstruction from the append-only
event log.

This enables:

- State reconstruction from events (time-travel debugging)
- Reproducibility verification (replay → same state)
- Audit trail replay
- Determinism testing

## Changes in Detail

### 1. Event Replay Logic

**File:** `components/workflow/src/ai/miniforge/workflow/replay.clj` (NEW)

Implement event stream replay:

- Load events from persistence
- Reconstruct workflow state step by step
- Verify state consistency
- Support partial replay (replay up to timestamp)

### 2. Event Log Loading

**File:** `components/workflow/src/ai/miniforge/workflow/persistence.clj`

Add event log loading functions:

- Load all events for a workflow
- Load events in time range
- Filter events by type

### 3. Public API

**File:** `components/workflow/src/ai/miniforge/workflow/interface.clj`

Export replay functions:

- `replay-workflow` - Reconstruct state from events
- `verify-determinism` - Check if same events → same state

### 4. Tests

**File:** `components/workflow/test/ai/miniforge/workflow/replay_test.clj` (NEW)

Test cases for:

- Event stream replay reconstructs correct state
- Partial replay works
- Determinism verification
- Missing events handled gracefully

## Testing Plan

```bash
bb test components/workflow
bb test
bb pre-commit
```

### Test Coverage

- ✅ Replay reconstructs workflow state from events
- ✅ Same events produce same state (determinism)
- ✅ Partial replay to specific timestamp works
- ✅ Missing/corrupt events handled gracefully
- ✅ State consistency validated

## Deployment Plan

This is a **new feature** with no breaking changes:

- Adds replay capability
- Existing workflows continue to work
- Replay is opt-in for debugging/audit

## Related Issues/PRs

**Depends On:**

- None (independent infrastructure)

**Blocks:**

- None (enables future features)

**Related:**

- N1 Architecture Spec §5.2 (Reproducibility)
- docs/N1-implementation-status.md
- docs/N1-completion-pr-plan.md

## Checklist

- [ ] Replay logic implemented
- [ ] Event log loading added
- [ ] State reconstruction works
- [ ] Tests added and passing
- [ ] Pre-commit hooks pass
- [ ] N1 spec conformance verified

## Conformance

This PR achieves **full conformance** with N1 Architecture Spec §5.2 for event stream replay.

**Before:** ❌ No replay capability  
**After:** ✅ Full event stream replay and determinism verification
