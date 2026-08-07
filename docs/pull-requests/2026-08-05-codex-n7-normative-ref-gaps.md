<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# work: complete N7 implementation references

## Overview

Adds direct normative references for every external N-series contract named by
the bounded N7 implementation tasks.

## Motivation

Copilot's review of #1645 found that requiring an N10 effect path without citing
N10 leaves implementers with incomplete traceability. The same audit found four
analogous omissions in the N7 task DAG.

## Layer

Planning foundations — normative-reference metadata only.

## Depends on

- [miniforge#1645](https://github.com/miniforge-ai/miniforge/pull/1645) — merged

## Changes in Detail

- Cite N6 and N10 for canonical OPSV actuation/effect records.
- Cite N4 for domain gate decisions.
- Cite N4, N8, and N10 for the CLI apply path.
- Repair stale case-sensitive N8 paths and cite the N11 runtime-adapter contract
  for governed Kubernetes actuation.
- Cite N7 for OPSV governance and N3 for workflow event emission.

## Testing Plan

- Parse every changed work spec as EDN.
- Verify every referenced normative document exists.
- Run the full pre-commit gate.

## Deployment Plan

Merge before the first N7 implementation PR.

## Checklist

- [x] Every explicit N-series dependency has a direct normative reference
- [x] No task scope, constraint, or acceptance criterion changed
