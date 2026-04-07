# feat/pr-train-events — PR2a

## Layer

Domain (PR Train)

## Depends on

- None (base of merge trigger stack)

## Overview

Adds event emission to the PR train manager so that `complete-merge` publishes
a `:pr/merged` event to a configurable event stream.

## Motivation

The meta-loop merge trigger system needs to know when a PR merges so it can
kick off downstream workflows (e.g., promoting the next PR in the train,
triggering evidence collection). By emitting events from `complete-merge`, the
PR train becomes observable without polling.

## Changes In Detail

| File | What changed |
|------|-------------|
| `components/pr-train/src/ai/miniforge/pr_train/core.clj` | Added `event-stream` field to `InMemoryPRTrainManager` defrecord; `create-manager` accepts optional opts map with `:event-stream`; `complete-merge` emits `:pr/merged` event via `requiring-resolve` |
| `components/pr-train/test/ai/miniforge/pr_train/event_stream_test.clj` | 2 new tests verifying event emission on merge and nil-stream safety |

**+81 LOC** across 2 files.

## Testing Plan

- [x] 2 unit tests for event emission (merge event content, nil-stream no-op)
- [ ] Pre-commit hooks pass (lint, format, test, graalvm)
- [ ] Integration with PR2b (merge trigger) once that lands

## Deployment Plan

No runtime impact — additive field on defrecord, backward-compatible
`create-manager` arity. Merges to `main` and is consumed by PR2b.

## Related Issues / PRs

- **PR2b** (merge trigger) depends on this PR
- Part of the meta-loop merge trigger work

## Checklist

- [x] Tests written and passing
- [x] No breaking changes to existing interfaces
- [x] Polylith interface boundaries respected
- [x] Babashka / GraalVM compatible (requiring-resolve pattern)
