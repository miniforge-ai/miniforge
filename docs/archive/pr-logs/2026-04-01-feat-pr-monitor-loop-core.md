<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: pr monitor loop core

## Layer

Application

## Depends on

- `feat/pr-monitor-loop-handlers`

## Overview

Adds the loop orchestration namespace on top of the extracted state and handler layers.

## Motivation

This branch restores the monitor loop behavior after the state and handler extractions, while keeping the loop core
itself reviewable as a focused unit.

## Changes in Detail

- Add the slimmed `monitor_loop.clj`.

## Testing Plan

- Run `bb pre-commit`

## Deployment Plan

- Merge before the final observe-phase wiring branch.

## Related Issues/PRs

- Parent feature: PR monitor loop
- Follow-up stack branch: `feat/pr-monitor-loop-observe`

## Checklist

- [x] Scope limited to loop orchestration
- [ ] `bb pre-commit` recorded
