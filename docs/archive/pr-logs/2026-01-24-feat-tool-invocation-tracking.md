<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Tool Invocation Tracking

## Overview

Add tool invocation tracking to capture per-call metadata and include it in evidence bundles.

## Motivation

N1 spec requires tool usage to be recorded for evidence and auditability.
We need a consistent mechanism to capture tool inputs/outputs, timing,
and errors and surface them in evidence bundles.

## Changes in Detail

- Add tool invocation tracking helpers and record invocations during tool execution.
- Attach tracking to agent execution and workflow phase execution contexts.
- Persist tool invocations into workflow state and include them in evidence bundle assembly.
- Extend evidence bundle schema/template and tests to cover tool invocations.

## Testing Plan

- `bb pre-commit`

## Deployment Plan

- No special deployment steps; merge and release with standard workflow.

## Related Issues/PRs

- N1 spec PR DAG: PR5 (tool invocation tracking).

## Checklist

- [x] Tool invocations recorded with id, timestamp, duration, args, result, exit-code, error
- [x] Evidence bundle includes tool invocations when present
- [x] Tests updated or added for tracking and evidence bundle inclusion
- [x] Docs updated if required
