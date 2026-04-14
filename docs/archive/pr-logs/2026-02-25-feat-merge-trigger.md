<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat/merge-trigger — PR2b

## Layer

Domain (Workflow)

## Depends on

- PR2a (feat/pr-train-events) — `:pr/merged` event emission from pr-train

## Overview

Adds event-driven merge triggers to the workflow component. When a `:pr/merged`
event is published to an event stream, matching trigger rules fire workflows
on background threads.

## Motivation

After PR2a made `complete-merge` emit `:pr/merged` events, we need a subscriber
that reacts to those events and kicks off downstream workflows (e.g., promoting
the next PR in the train, deploying, collecting evidence). This decouples the
PR train from workflow execution via the event bus.

## Changes In Detail

| File | What changed |
|------|-------------|
| `components/workflow/src/ai/miniforge/workflow/trigger.clj` | New file: `matches-trigger?`, `extract-input`, `load-trigger-config`, `create-merge-trigger`, `stop-trigger!` |
| `components/workflow/src/ai/miniforge/workflow/interface.clj` | Added Layer 6b with `create-merge-trigger` and `stop-trigger!` via `requiring-resolve` |
| `components/workflow/test/ai/miniforge/workflow/trigger_test.clj` | 9 tests: trigger matching (7), input extraction (3), integration (2) |

## Testing Plan

- [x] 7 unit tests for `matches-trigger?` (repo match/mismatch, branch-pattern match/mismatch, event type mismatch, nil
  repo, nil branch-pattern)
- [x] 3 unit tests for `extract-input` (mapping, nil mapping, missing event key)
- [x] 2 integration tests for `create-merge-trigger` (fires on match, ignores non-match)
- [ ] Pre-commit hooks pass (lint, format, test, graalvm)

## Deployment Plan

No runtime impact — new opt-in trigger API. Consumers must explicitly call
`create-merge-trigger` to activate. Backward-compatible addition to the
workflow interface.

## Related Issues / PRs

- **PR2a** (pr-train-events) — prerequisite, provides `:pr/merged` event emission
- Part of the meta-loop merge trigger work

## Checklist

- [x] Tests written
- [x] No breaking changes to existing interfaces
- [x] Polylith interface boundaries respected (`requiring-resolve` for cross-component calls)
- [x] Babashka / GraalVM compatible (requiring-resolve pattern)
- [x] License header on all new files
- [x] Layer comments on all new files
