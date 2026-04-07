# feat: Repository Intelligence and Context Assembly

**Branch:** `feat/repo-context-assembly`
**Base:** `main`
**Date:** 2026-03-04
**Layer:** Domain Model
**Depends On:** None
**Blocks:** Implementation PRs for repo-index, context-pack, and tool-contract components

## Overview

Adds normative spec for repository indexing, context assembly, and agent tool contracts to
N1. This eliminates unbounded full-repo reads by agents, replacing them with
content-addressed Context Packs assembled by the orchestrator under enforced budgets.

## Motivation

Every agent invocation currently reads repository files ad-hoc, leading to:

- Token waste from repeated full-file reads across agents in the same task
- No auditability of which code ranges influenced generated edits
- No deduplication of overlapping context across concurrent agents
- No enforcement of retrieval budgets

This spec addition defines the abstraction boundary between agents and repositories,
making all context retrieval bounded, content-addressed, deduplicated, and auditable.

## Changes in Detail

### N1-architecture.md (v0.3.0 → v0.4.0)

**New domain entities (§2.27–§2.30):**

| Section | Entity | Description |
|---------|--------|-------------|
| §2.27 | Repo Index | Content-addressed index of repo@commit with files, symbols, edges, search, coverage |
| §2.27.1 | File Record | Per-file blob inventory with `is-generated?` determination rules |
| §2.27.2 | Range | Atomic content-addressed citation unit; normative UTF-8/LF/1-based rules |
| §2.27.3 | Symbol | Dual-identity scheme: commit-scoped `symbol/id` + cross-commit `symbol/key` |
| §2.27.4 | Edge | Typed graph edges (import, ref, call, implements, inherits, tests, etc.) |
| §2.27.5 | Search Config | Lexical (BM25) + optional vector search backends behind artifact refs |
| §2.27.6 | Repo Map Config | Token-budgeted profiles (tiny/default/large) |
| §2.27.7 | Coverage Record | Drives tool availability; errors vs empty for `:none` coverage |
| §2.28 | Context Pack | Bounded context document: repo-map slice, symbols, snippets, citations |
| §2.28.4 | Citation | Six modes: edit-target, evidence, justification, novel-code, synthesis, policy-output |
| §2.28.5 | Why Record | Structured retrieval justification (replaces free-text `why`) |
| §2.28.6 | Constraint Envelope | Budget limits including search metering |
| §2.28.7 | Policy Envelope | Separable budget/exhaustion policy for independent tuning |
| §2.29 | Staleness | Detection via blob-sha mismatch; response: rebuild, merge, or fail |
| §2.30 | Tool Contract | Agent-facing (repo.map/search/symbol/open, nav.*) + orchestrator-only tools |
| §2.30.4 | Budget Accounting | All tools metered including search calls and preview tokens |
| §2.30.5 | Phase Budgets | Default ceilings per workflow phase (plan/implement/test/review) |

**New conformance section (§11):**

- N1.RI.1–N1.RI.7: Repository Index requirements (MUST + SHOULD)
- N1.CP.1–N1.CP.5: Context Pack requirements
- N1.AU.1–N1.AU.4: Auditability requirements (links to N6 evidence bundles)
- N1.SI.1–N1.SI.3: Staleness and invalidation requirements

**Updated sections:**

- §12 References: Added SCIP and LSIF specification links
- §13 Glossary: Added Context Pack, Context Staleness, Policy Envelope, Range, Repo Index, Repo Map, Symbol Key
- Version history: Added 0.4.0-draft entry

### SPEC_INDEX.md (v0.4.0 → v0.5.0)

- Added Repository Intelligence and Context Assembly to N1 "Defines" list

### work/repo-context-assembly.spec.edn (new)

- WIP EDN schemas for all domain entities, tool contract, and default budgets
- Cross-references N1 section numbers
- Includes tool guardrails and coverage requirements

## Key Design Decisions

1. **Dual symbol identity** (`symbol/id` + `symbol/key`): Prevents cache invalidation
   churn on refactors while maintaining commit-scoped precision.

2. **Separated Policy Envelope**: Budget tuning and A/B testing without rebuilding
   Context Packs.

3. **Search metering**: All tool calls — including search previews — count against
   budgets. Prevents token waste from over-querying.

4. **Citation modes with explicit exceptions**: `novel-code` and `synthesis` modes for
   edits that don't map to source ranges, rather than requiring impossible citations.

5. **Staleness protocol**: v1 defaults to invalidate-and-rebuild; merge is opt-in for non-overlapping changes only.

6. **`repo.open` hard caps**: 200 lines / 50 KB per call; rejects full-file reads when symbol exists in index.

## Testing Plan

- [ ] N1 markdown renders correctly (section numbering, tables, code blocks)
- [ ] All N1.RI/CP/AU/SI requirement IDs are unique and referenced
- [ ] EDN schema in `work/` parses cleanly
- [ ] Cross-references between §2.27–§2.30 and §11 are consistent
- [ ] Glossary entries alphabetically sorted and link to correct sections
- [ ] Version history accurately reflects changes

## Conformance

**Before:** No normative requirements for repository context assembly. Agents read repos ad-hoc.
**After:** N1 §2.27–§2.30 define domain entities; §11 defines MUST/SHOULD conformance requirements;
tool contract (§2.30) enforces bounded, auditable retrieval.

## Related Issues/PRs

- Blocks: Implementation of `repo-index` Polylith component
- Blocks: Implementation of `context-pack` Polylith component
- Blocks: MCP tool contract registration in tool registry
- References: N6 §3.2 artifact provenance (audit traces link to evidence bundles)

## Checklist

- [x] N1 spec additions (§2.27–§2.30, §11, glossary, version history)
- [x] SPEC_INDEX.md updated
- [x] WIP EDN schema in work/
- [x] PR documentation
- [ ] Implementation plan (separate deliverable)
