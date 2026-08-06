<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: align PR-budget help with its enforced ceiling

## Overview

Correct the `bb pr-budget` task help to report the enforced 600-line PR ceiling.

## Motivation

The PR-budget implementation and repository standards both define a 600-line
ceiling, but `bb tasks` still described the original 200-line value. A command's
operator-facing help must not disagree with the gate it invokes.

## Layer

Development-tool task metadata.

## Changes in Detail

- Replace the stale 200-line value in the `pr-budget` task description with 600.
- Preserve the distinct 200-line commit budget and all existing exclusions.

## Standards Audit

- The help text now agrees with `pr-budget/default-budget` and `AGENTS.md`.
- No executable behavior, namespace, component dependency, or data shape changes.
- The correction is isolated from the N7 contract PR where the gap was found.

## Testing Plan

- Load and list Babashka tasks.
- Run the pre-commit hook.
- Confirm the PR-size gate remains within budget.

## Deployment Plan

No deployment action is required.

## Checklist

- [x] PR task help says 600 reportable lines.
- [x] Commit task help remains 200 reportable lines.
- [x] Runtime threshold remains unchanged.
