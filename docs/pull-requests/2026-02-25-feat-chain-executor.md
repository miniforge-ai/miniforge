<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat(workflow): Add chain executor for sequential workflow chaining

**Branch**: `feat/chain-executor`
**Date**: 2026-02-25
**Status**: Open

## Overview

Add a chain executor that runs a sequence of workflows where each step's output feeds into the next step's input via configurable bindings. This builds on PR1a's `:execution/output` addition to `run-pipeline`.

## Motivation

Complex tasks often require multiple workflows executed in sequence (e.g., plan then implement). The chain executor provides a declarative way to define these multi-step pipelines with data flowing between steps via input bindings.

## Changes in Detail

### New Files

| File | Purpose |
|------|---------|
| `components/workflow/src/ai/miniforge/workflow/chain.clj` | Chain executor with binding resolution and sequential execution |
| `components/workflow/test/ai/miniforge/workflow/chain_test.clj` | Tests for binding resolution and chain execution |

### Modified Files

| File | Changes |
|------|---------|
| `components/workflow/src/ai/miniforge/workflow/interface.clj` | Added `run-chain` to Layer 5 public API |

## Architecture

### Binding Resolution

Input bindings map keys to path expressions that resolve against previous step output and chain input:

- **String literal** — passed through as-is
- **`:chain/input.KEY`** — reads KEY from the chain's initial input
- **`[:prev/phase-results ...]`** — navigates into previous step's `:execution/output`
- **`[:prev/artifacts ...]`** — navigates into previous step's artifacts
- **`[:prev/last-phase-result ...]`** — navigates into previous step's last phase result
- **Keyword** — reads from chain input

### Chain Execution

`run-chain` loops through steps sequentially. Each step:

1. Resolves input bindings against previous output and chain input
2. Loads the workflow via `load-workflow`
3. Calls `runner/run-pipeline` with resolved input
4. Extracts `:execution/output` for the next step
5. Stops immediately on failure

## Testing

- `resolve-binding` handles all four path types (string, `:chain/input.*`, vector, keyword)
- `resolve-bindings` resolves a complete bindings map
- `run-chain` with 2 steps verifies output flows from step 1 to step 2
- `run-chain` stops on failure — step 1 fails, step 2 never runs
