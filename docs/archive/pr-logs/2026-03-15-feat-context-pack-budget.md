<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Add context-pack component with per-phase budget enforcement

## Overview

Adds a new `components/context-pack/` component that wraps repo-map + files +
search results into bounded, auditable context documents with per-phase token
budgets. Wired into the implement phase to replace ad-hoc context assembly.

This is Iteration 3 of the Repo Indexer plan.

## Motivation

Previously, context assembly was ad-hoc — the implement phase independently
loaded repo-map, files, and search results without coordinated budget tracking.
Files that exceeded the token budget were included anyway, wasting tokens.
There was no way to audit how much of the budget each source consumed.

Context-pack provides:

- Per-phase token budgets (plan: 1200, implement: 3500, test: 2200, review: 2800)
- Pre-flight budget checks before including each item
- Deduplication of files across sources (direct include vs search results)
- Audit trail showing per-source token consumption and utilization

## Changes in Detail

### New: `components/context-pack/`

| File | Purpose |
|------|---------|
| `config.clj` | Load budgets from EDN resource |
| `factory.clj` | Factory functions: `->context-pack`, `->budget-audit`, `->source` |
| `schema.clj` | Malli schemas: ContextPack, BudgetAudit, Source |
| `budget.clj` | Token estimation, budget tracking, exhaustion detection |
| `dedup.clj` | Deduplicate files by path, search results by score, cross-source |
| `builder.clj` | Assemble packs: `build-pack`, `extend-pack`, `audit` |
| `interface.clj` | Public API |
| `budgets.edn` | Phase budget config (EDN resource) |

### Modified: `components/phase-software-factory/`

- `deps.edn`: Added context-pack dependency
- `implement.clj`: Replaced `build-repo-map-context` with `build-context-pack`
  which uses `context-pack/build-pack` for coordinated budget-enforced assembly;
  cached as `execution/pack-context` for retries; context pack passed to agent
  via `:task/context-pack`

## Testing Plan

- [x] 11 tests: budget queries, pack building, schema conformance, search
  integration, budget enforcement, exhaustion detection, audit snapshots,
  extend-pack, tokens-remaining, deduplication
- [x] Implement phase tests pass
- [ ] Dogfood: compare token usage with context-pack vs ad-hoc assembly

## Related

- Iteration 1: PR #317 (scanner + repo map)
- Iteration 2: PR #320 (BM25 search)
- Spec: N1-architecture.md §2.28–§2.30

## Checklist

- [x] Context-pack component scaffold
- [x] Budget config in EDN
- [x] Factory functions for all domain maps
- [x] Schemas for all types
- [x] Budget enforcement (fail-closed by default)
- [x] Deduplication (files, search results, cross-source)
- [x] Implement phase wiring
- [x] Tests passing
- [x] PR documentation
