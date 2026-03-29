# Repository Intelligence & Context Assembly — Implementation Plan

**Date:** 2026-03-04
**Spec:** N1 §2.27–§2.30, §11 (PR #247)
**Style:** Waterfall phases, DAG within each phase

---

## Phase 0: Observe (1 week)

**Goal:** Calibrate budgets empirically before enforcing them.

```text
0.1 Instrument existing agent calls
     ├── Add retrieval logging to orchestrator (repo-id, path, lines, tokens)
     ├── Emit context-retrieval events to event-stream (N3)
     └── Wire to evidence-bundle for persistence (N6)
0.2 Run observe-only for ≥5 real workflows
     ├── Collect: files opened, lines read, tokens consumed, dedup opportunities
     └── Output: baseline-metrics.edn (actual usage per phase)
0.3 Calibrate default budgets
     └── Adjust §2.30.5 defaults based on observed P50/P95 per phase
```

**Deliverables:**

| ID | Artifact | Type |
|----|----------|------|
| 0.1 | Retrieval logging in orchestrator | Code (modify `orchestrator`) |
| 0.2 | Context retrieval event schema | Schema (amend N3) |
| 0.3 | baseline-metrics.edn | Data artifact |

**Exit gate:** Baseline metrics collected for ≥5 workflows across plan/implement/test.

---

## Phase 1: Core Index + Map + Search (3 weeks)

**Goal:** Ship `repo.map` + `repo.search-lex` + `repo.open` with budget enforcement
and audit logging. This is the minimum viable context assembly.

### DAG

```text
1.1 repo-index component (Polylith)
     ├── 1.1.1 schema.clj — Malli schemas for RepoIndex, FileRecord, Range, Coverage
     ├── 1.1.2 scanner.clj — Walk git tree, compute blob-sha, detect is-generated?
     ├── 1.1.3 incremental.clj — Diff tree-sha, index only changed blobs
     ├── 1.1.4 repo-map.clj — Build token-budgeted repo map (file summaries)
     ├── 1.1.5 search-lex.clj — BM25 index (tantivy-clj or custom postings)
     ├── 1.1.6 storage.clj — Persist index manifest + heavyweight artifacts behind refs
     └── 1.1.7 interface.clj — Layer 0-3 public API

1.2 context-pack component (Polylith)                        [depends: 1.1]
     ├── 1.2.1 schema.clj — Malli schemas for ContextPack, ConstraintEnvelope,
     │                       PolicyEnvelope, Citation, WhyRecord
     ├── 1.2.2 builder.clj — Assemble ContextPack from RepoIndex + query
     ├── 1.2.3 budget.clj — Enforce limits, track usage, handle exhaustion
     ├── 1.2.4 dedup.clj — Deduplicate snippets by (blob-sha, range) across agents
     ├── 1.2.5 audit.clj — Log retrieval ops, link to N6 evidence bundles
     └── 1.2.6 interface.clj — Layer 0-3 public API

1.3 Tool registration                                        [depends: 1.1, 1.2]
     ├── 1.3.1 Register repo.index-status, repo.map, repo.search-lex, repo.open
     │         in tool-registry (resources/tools/*.edn)
     ├── 1.3.2 Implement tool handlers in repo-index + context-pack
     └── 1.3.3 Add budget accounting to each tool handler (§2.30.4)

1.4 Orchestrator integration                                 [depends: 1.3]
     ├── 1.4.1 context.build — Assemble initial ContextPack for agent invocation
     ├── 1.4.2 context.extend — Handle agent requests for additional context
     ├── 1.4.3 context.audit — Expose budget + source trace query
     ├── 1.4.4 Wire ContextPack into agent invocation protocol (N1 §6.1)
     └── 1.4.5 Enforce agent MUST NOT call orchestrator-only tools

1.5 Agent protocol changes                                   [depends: 1.4]
     ├── 1.5.1 Modify agent context handoff to include ContextPack (§6.3)
     ├── 1.5.2 Add repo.map + repo.search-lex to agent tool allowlist
     ├── 1.5.3 Block direct repo reads (enforce N1.CP.2)
     └── 1.5.4 Update LLM system prompts to use search-before-open protocol

1.6 Audit integration                                        [depends: 1.2.5]
     ├── 1.6.1 Emit context-pack-built event (N3)
     ├── 1.6.2 Emit context-extended event (N3)
     ├── 1.6.3 Link audit traces to evidence bundles (N6 §3.2)
     └── 1.6.4 Add context audit to workflow evidence bundle
```

**Component dependency graph:**

```text
repo-index ──► context-pack ──► orchestrator
     │              │                │
     ▼              ▼                ▼
tool-registry  event-stream    agent (modified)
                    │
                    ▼
             evidence-bundle
```

**Deliverables:**

| ID | Artifact | Type | Polylith Component |
|----|----------|------|--------------------|
| 1.1 | Repo Index | New component | `components/repo-index/` |
| 1.2 | Context Pack | New component | `components/context-pack/` |
| 1.3 | Tool registrations | Config + handlers | `tool-registry` resources |
| 1.4 | Orchestrator wiring | Modify existing | `components/orchestrator/` |
| 1.5 | Agent protocol | Modify existing | `components/agent/` |
| 1.6 | Audit events | Schema + emitters | `event-stream`, `evidence-bundle` |

**Exit gate:** End-to-end workflow runs with ContextPack-only context; repo.open
blocked without prior search; budget enforcement logged; audit trace queryable.

---

## Phase 2: Symbols + Tree-sitter (2 weeks)

**Goal:** Add symbol extraction via Tree-sitter for all target languages.
Enables `repo.symbol` tool and signature-first context retrieval.

### DAG

```text
2.1 Tree-sitter integration                                  [depends: Phase 1]
     ├── 2.1.1 Evaluate JNI/subprocess approach for tree-sitter from JVM
     │         (tree-sitter CLI → JSON AST, or java-tree-sitter bindings)
     ├── 2.1.2 Language grammars: Clojure, TypeScript, Python, Go, Rust
     └── 2.1.3 AST → Symbol extraction (kind, name, fqname, ranges, visibility)

2.2 Symbol table                                             [depends: 2.1]
     ├── 2.2.1 symbol-id generation (deterministic hash scheme per §2.27.3)
     ├── 2.2.2 symbol-key generation (cross-commit logical identity)
     ├── 2.2.3 Container hierarchy (class → method, namespace → function)
     ├── 2.2.4 Edge extraction: imports (file-level), member-of (symbol-level)
     └── 2.2.5 Incremental symbol update (only re-parse changed blobs)

2.3 repo.symbol tool                                         [depends: 2.2]
     ├── 2.3.1 Register in tool-registry
     ├── 2.3.2 Implement handler (signature/doc by default; body opt-in)
     ├── 2.3.3 Budget accounting (body counts against snippet tokens)
     └── 2.3.4 Update agent prompts: prefer symbol-id over repo.open

2.4 Context Pack symbol integration                          [depends: 2.2, 2.3]
     ├── 2.4.1 context.build uses symbols for relevant matches
     ├── 2.4.2 ContextPack :context/symbols populated from search results
     └── 2.4.3 Citation generation uses symbol ranges
```

**Language-specific notes:**

| Language | Symbol Kinds | Special Handling |
|----------|-------------|------------------|
| Clojure | defn, defmulti, defmethod, defprotocol, ns, def | Macro-generated code → `is-generated?` |
| TypeScript | class, function, method, interface, type, const | JSX/TSX files need tsx grammar |
| Python | class, function, method, variable | Decorators as modifiers |
| Go | func, type, struct, interface, const, var | Generated protobuf stubs → `is-generated?` |
| Rust | fn, struct, enum, trait, impl, const, mod | `#[derive]` → generated impls |

**Exit gate:** Symbol extraction works for all 5 languages; `repo.symbol` returns
signature/doc for any indexed symbol; agents use symbol-id before repo.open.

---

## Phase 3: SCIP/LSIF Precision Navigation (2 weeks)

**Goal:** Add precise def/refs/impls/calls from SCIP or LSIF output.
Enables `nav.*` tools for languages with SCIP coverage.

### DAG

```text
3.1 SCIP ingestion                                           [depends: Phase 2]
     ├── 3.1.1 Parse SCIP index protobuf (scip.proto → Clojure records)
     ├── 3.1.2 Map SCIP symbols to repo-index symbol-ids
     ├── 3.1.3 Extract edges: def, ref, call, implements, inherits
     ├── 3.1.4 Update coverage record (:scip? true, :refs? :precise, etc.)
     └── 3.1.5 Incremental: re-ingest only when SCIP index changes

3.2 LSIF fallback                                            [depends: 3.1]
     ├── 3.2.1 Parse LSIF JSON-lines format
     ├── 3.2.2 Map LSIF vertices/edges to repo-index model
     └── 3.2.3 Update coverage record

3.3 Graph navigation tools                                   [depends: 3.1]
     ├── 3.3.1 Register nav.def, nav.refs, nav.impls, nav.calls
     ├── 3.3.2 Implement handlers with coverage gating (error when :none)
     ├── 3.3.3 Budget accounting for nav calls
     └── 3.3.4 Update agent prompts for graph-aware navigation

3.4 SCIP generation pipeline                                 [depends: 3.1]
     ├── 3.4.1 TypeScript: scip-typescript (npm package)
     ├── 3.4.2 Go: scip-go
     ├── 3.4.3 Python: scip-python
     ├── 3.4.4 Rust: rust-analyzer SCIP export
     └── 3.4.5 Clojure: evaluate clj-kondo → SCIP bridge or custom emitter
```

**SCIP availability by language:**

| Language | SCIP Indexer | Maturity | Notes |
|----------|-------------|----------|-------|
| TypeScript | scip-typescript | Stable | Best coverage |
| Go | scip-go | Stable | |
| Python | scip-python | Beta | Some edge cases |
| Rust | rust-analyzer | Stable | Via `--emit scip` |
| Clojure | None | N/A | Needs custom bridge from clj-kondo analysis |

**Exit gate:** `nav.def` + `nav.refs` work for ≥1 SCIP-covered language; coverage
gating returns proper errors for uncovered languages.

---

## Phase 4: Staleness + Semantic Search (2 weeks)

**Goal:** Handle concurrent edits and add vector search.

### DAG

```text
4.1 Staleness detection                                      [depends: Phase 1]
     ├── 4.1.1 Track blob-sha → agent mapping in context-pack
     ├── 4.1.2 Detect tree changes during agent execution
     ├── 4.1.3 Implement invalidate-and-rebuild (default)
     └── 4.1.4 Implement fail-on-staleness (when rebuild budget exhausted)

4.2 Semantic search                                          [depends: Phase 2]
     ├── 4.2.1 Evaluate embedding options (local model vs API)
     ├── 4.2.2 Embed symbol signatures + docstrings
     ├── 4.2.3 Embed file summaries from repo map
     ├── 4.2.4 Vector store (hnswlib-clj or similar)
     ├── 4.2.5 Register repo.search-sem tool
     └── 4.2.6 Budget accounting for semantic search calls

4.3 Merge support (optional, stretch)                        [depends: 4.1]
     ├── 4.3.1 Compute delta between old and new tree
     ├── 4.3.2 Patch ContextPack for non-overlapping changes
     └── 4.3.3 Fail when overlap detected
```

**Exit gate:** Staleness detected and handled for concurrent agent scenarios;
semantic search returns relevant symbols for natural language queries.

---

## Full DAG (cross-phase dependencies)

```text
Phase 0: Observe
  0.1 → 0.2 → 0.3
                 │
Phase 1: Core    ▼
  1.1 ──────────────► 1.2 ──────► 1.4 ──► 1.5
   │                   │           │
   └──► 1.3 ◄─────────┘           │
         │                         │
         └─────────────────────────┘
                                   │
  1.6 ◄───────────────────────────┘
   │
Phase 2: Symbols ▼
  2.1 ──► 2.2 ──► 2.3 ──► 2.4
                            │
Phase 3: SCIP/LSIF          ▼
  3.1 ──► 3.3               │
   │                         │
  3.2 ──┘                   │
   │                         │
  3.4 (parallel, per-lang)  │
                             │
Phase 4: Staleness + Vectors ▼
  4.1 (from Phase 1)
  4.2 (from Phase 2) ──► 4.2.5
  4.3 (from 4.1, stretch)
```

---

## PR Layering Strategy

Per project convention (rule 720: <400 lines, independently mergeable):

| PR | Phase | Scope | ~Lines | Depends On |
|----|-------|-------|--------|------------|
| #247 | — | Spec additions (this PR) | 809 | — |
| P1-A | 0 | Retrieval instrumentation + observe metrics | ~250 | #247 |
| P1-B | 1.1 | repo-index component (scanner + incremental + storage) | ~400 | P1-A |
| P1-C | 1.1 | repo-map + search-lex | ~350 | P1-B |
| P1-D | 1.2 | context-pack component (builder + budget + dedup) | ~400 | P1-C |
| P1-E | 1.3–1.4 | Tool registration + orchestrator wiring | ~350 | P1-D |
| P1-F | 1.5–1.6 | Agent protocol + audit integration | ~300 | P1-E |
| P2-A | 2.1 | Tree-sitter integration + AST extraction | ~400 | P1-F |
| P2-B | 2.2–2.4 | Symbol table + repo.symbol tool + context integration | ~400 | P2-A |
| P3-A | 3.1 | SCIP ingestion + edge extraction | ~400 | P2-B |
| P3-B | 3.2–3.3 | LSIF fallback + nav tools | ~350 | P3-A |
| P3-C | 3.4 | SCIP generation pipelines (per-language) | ~300 | P3-A |
| P4-A | 4.1 | Staleness detection + invalidate-and-rebuild | ~300 | P1-F |
| P4-B | 4.2 | Semantic search (embeddings + vector store) | ~400 | P2-B |

---

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Tree-sitter JVM integration is brittle | Medium | High | Fallback: subprocess + JSON AST; evaluate java-tree-sitter early |
| Clojure has no SCIP indexer | Certain | Medium | Phase 3: build clj-kondo → symbol bridge; defer precise nav |
| Budget defaults too tight for large repos | Medium | Medium | Phase 0 observe-only calibration; per-repo overrides |
| Staleness rebuilds too expensive | Low | Medium | Defer merge support to Phase 4.3; most workflows are single-agent |
| BM25 index too slow for large repos | Low | Low | Use tantivy (Rust) via JNI; proven at scale |

---

## Success Metrics

| Metric | Baseline (Phase 0) | Target (Phase 1) | Target (Phase 4) |
|--------|-------------------|-------------------|-------------------|
| Tokens per agent call (context) | Measure | -40% | -60% |
| Duplicate snippets across agents | Measure | 0 (dedup) | 0 (dedup) |
| Context retrieval audit coverage | 0% | 100% | 100% |
| Symbol-first retrievals (vs raw open) | 0% | N/A | >70% |
| Cache hit rate (cross-commit symbol-key) | N/A | N/A | >50% |
