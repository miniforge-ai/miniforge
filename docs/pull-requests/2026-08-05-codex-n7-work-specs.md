<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# work: replace stale N7 implementation specs

## Overview

Replaces the obsolete OPSV task descriptions with a dependency-ordered,
spec-conforming implementation queue for the complete seven-phase N7 contract.

## Motivation

The existing tasks describe a five-phase workflow with deferred stubs, treat
requested apply intent as authority, omit the HPA/KEDA minimal use case, and
misclassify character counts as token limits. Agents following them would build
the pre-reconciliation contract.

## Layer

Planning — implementation work specs and queue metadata only.

## Depends on

- [miniforge#1639](https://github.com/miniforge-ai/miniforge/pull/1639) — merged

## Changes in Detail

- Replace stale N7 tasks with seven bounded implementation strata.
- Make contracts, governance, workflow, effects, and UX dependencies explicit.
- Move agent-budget convergence after the HPA/KEDA minimal compliant use case.
- Repair directly related theme and convergence-spec metadata.

## Test plan

- Parse every changed work spec as EDN.
- Regenerate `work/QUEUE.md` with `bb work:queue`.
- Run the full pre-commit gate.

## Deployment Plan

Merge before the first OPSV implementation component PR.

## Checklist

- [x] Every task has all required work-spec fields
- [x] Every acceptance criterion is a single testable assertion
- [x] Dependencies name live task files
- [x] Queue and full repository checks pass
