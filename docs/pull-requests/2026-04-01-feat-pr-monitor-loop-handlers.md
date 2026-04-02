# feat: pr monitor loop handlers

## Layer

Application

## Depends on

- `feat/pr-monitor-loop-state`

## Overview

Splits the monitor comment handlers into their own namespace.

## Motivation

Keeping routing and handler behavior separate from loop state and loop execution makes the monitor stack reviewable in
smaller slices and keeps the eventual loop core branch under the small-PR limit.

## Changes in Detail

- Add `monitor_handlers.clj`.

## Testing Plan

- Run `bb pre-commit`

## Deployment Plan

- Merge before the loop core branch.

## Related Issues/PRs

- Parent feature: PR monitor loop
- Follow-up stack branch: `feat/pr-monitor-loop-core`

## Checklist

- [x] Scope limited to comment handling and routing
- [ ] `bb pre-commit` recorded
