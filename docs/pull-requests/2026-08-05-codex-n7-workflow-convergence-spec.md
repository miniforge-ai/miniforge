<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# work: bound workflow-convergence implementation task

## Overview

Replaces the oversized workflow-selection convergence task description with a
bounded work spec that depends on the canonical N7 domain-policy and governed
actuation implementation tasks introduced in #1644.

## Motivation

The legacy task mixed current state, target state, six implementation groups,
testing strategy, and deferred follow-ons in one agent prompt. It also duplicated
the N7 convergence and authority contracts instead of depending on them.

## Layer

Planning — one work spec and its generated queue row.

## Depends on

- [miniforge#1644](https://github.com/miniforge-ai/miniforge/pull/1644) — merged

## Changes in Detail

- Preserve the registered-workflow selection outcome and heuristic fallback.
- Make offline evidence, frozen-holdout verification, and N10 publication gates
  explicit.
- Leave workflow synthesis out of scope rather than embedding deferred tasks.
- Remove volatile file-location and implementation-step instructions.

## Test plan

- Parse the work spec as EDN and validate required fields.
- Regenerate `work/QUEUE.md` with `bb work:queue`.
- Run the full pre-commit gate.

## Deployment Plan

Merge before any workflow-selection convergence implementation begins.

## Checklist

- [x] Required work-spec and priority fields are present
- [x] Acceptance criteria are single testable assertions
- [x] Dependencies name live task files and DAG identifiers
- [x] Deferred synthesis work is outside the task description
