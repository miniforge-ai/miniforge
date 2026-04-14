<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: pr monitor loop state

## Layer

Application

## Depends on

- `feat/pr-monitor-loop-poller`

## Overview

Splits shared monitor state and persistence concerns into their own namespace.

## Motivation

The original monitor loop implementation bundled state, handlers, and looping into one oversized file. Extracting shared
state first keeps the remaining PRs small and reviewable.

## Changes in Detail

- Add `monitor_state.clj`.

## Testing Plan

- Run `bb pre-commit`

## Deployment Plan

- Merge before handler and loop orchestration branches.

## Related Issues/PRs

- Parent feature: PR monitor loop
- Follow-up stack branch: `feat/pr-monitor-loop-handlers`

## Checklist

- [x] Scope limited to shared monitor state
- [ ] `bb pre-commit` recorded
