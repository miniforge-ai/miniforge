# feat: pr monitor loop budget events

## Layer

Foundations

## Depends on

- `feat/pr-monitor-loop-classifier`

## Overview

Adds PR monitor budget tracking and the monitor-specific event constructors.

## Motivation

The monitor loop needs hard-stop budget state and a stable event vocabulary before pollers or loop orchestration are
introduced.

## Changes in Detail

- Add `monitor_budget.clj`.
- Add `monitor_events.clj`.
- Add focused budget tests.

## Testing Plan

- Run `bb pre-commit`

## Deployment Plan

- Merge after the classifier and before the poller and loop branches.

## Related Issues/PRs

- Parent feature: PR monitor loop
- Follow-up stack branch: `feat/pr-monitor-loop-poller`

## Checklist

- [x] Scope limited to budget state and event modeling
- [ ] `bb pre-commit` recorded
