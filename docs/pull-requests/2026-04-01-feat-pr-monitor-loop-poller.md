# feat: pr monitor loop poller

## Layer

Infrastructure

## Depends on

- `feat/pr-monitor-loop-budget-events`

## Overview

Adds the GitHub PR polling adapter and watermark persistence for the monitor loop.

## Motivation

The loop needs a concrete adapter for reading open PRs and new comments before any handlers or continuous monitor loop
can run.

## Changes in Detail

- Add `pr_poller.clj`.

## Testing Plan

- Run `bb pre-commit`

## Deployment Plan

- Merge after the classifier and budget/event layers.

## Related Issues/PRs

- Parent feature: PR monitor loop
- Follow-up stack branch: `feat/pr-monitor-loop-state`

## Checklist

- [x] Scope limited to GitHub polling and persistence
- [ ] `bb pre-commit` recorded
