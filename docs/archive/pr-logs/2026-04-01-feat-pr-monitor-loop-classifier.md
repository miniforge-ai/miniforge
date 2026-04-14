<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: pr monitor loop classifier

## Layer

Domain

## Depends on

- `feat/pr-monitor-loop-spec`

## Overview

Adds comment classification for PR review comments plus focused unit coverage.

## Motivation

The monitor loop needs a reviewable, testable way to distinguish change requests, questions, approvals, bot comments,
and noise before any orchestration is added.

## Changes in Detail

- Add `classifier.clj`.
- Add focused classifier tests.

## Testing Plan

- Run `bb pre-commit`

## Deployment Plan

- Merge after the work spec and before polling/orchestration branches.

## Related Issues/PRs

- Parent feature: PR monitor loop
- Follow-up stack branch: `feat/pr-monitor-loop-budget-events`

## Checklist

- [x] Scope limited to comment classification
- [ ] `bb pre-commit` recorded
