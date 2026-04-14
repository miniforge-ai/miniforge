<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat/chain-evidence — PR6a

## Layer

Domain (Evidence Bundle, Workflow)

## Depends on

- PR1b: Chain schema and executor (for chain.clj modifications)

## Overview

Adds chain-level evidence metadata so that when a chain of workflows runs, the
evidence bundle knows which chain and step each workflow belongs to, and can
aggregate per-step evidence into a chain-level bundle.

## Motivation

When workflows execute as part of a chain, the individual evidence bundles lack
context about their position in the chain. This makes it impossible to
reconstruct the full chain execution from evidence alone. Chain evidence
metadata fills this gap by tagging each step result with its chain ID and index,
and providing aggregation functions to build a chain-level evidence record.

## Changes In Detail

| File | What changed |
|------|-------------|
| `components/evidence-bundle/src/ai/miniforge/evidence_bundle/chain_evidence.clj` | New file — `create-chain-evidence`, `summarize-step`, `aggregate-metrics` |
| `components/evidence-bundle/src/ai/miniforge/evidence_bundle/interface.clj` | Layer 7b — chain evidence interface wrappers + require |
| `components/workflow/src/ai/miniforge/workflow/chain.clj` | Added `:step/chain-id`, `:step/chain-index`, `:chain/step-count` to results |
| `components/evidence-bundle/test/ai/miniforge/evidence_bundle/chain_evidence_test.clj` | 6 tests covering success, failure, step-bundles, summarization, and metrics |
| `docs/pull-requests/2026-02-25-feat-chain-evidence.md` | This PR doc |

## Testing Plan

- [x] 6 unit tests for chain evidence functions
- [ ] Integration verification with full chain execution

## Deployment Plan

Additive only — new functions and metadata fields on existing maps.
No breaking changes. Ships with next release.

## Related Issues / PRs

- PR1b: Chain schema and executor (prerequisite)
- PR6b: Evidence content hashing (sibling)

## Checklist

- [x] Tests written and passing
- [x] No breaking changes to existing interfaces
- [x] Polylith interface boundaries respected
- [x] Babashka / GraalVM compatible
