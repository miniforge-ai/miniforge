# feat: Add BM25 lexical search to repo-index

## Overview

Adds keyword search to the repo-index component so agents can find relevant files
by searching instead of relying on the orchestrator to guess which files to include.

This is Iteration 2 of the Repo Indexer plan.

## Motivation

With Iteration 1, agents get a repo map (table of contents) but still can't search
for specific code patterns, function names, or error messages. They only see files
the orchestrator pre-selects. BM25 search lets agents find the 2-3 files they
actually need, expected to reduce token usage by ~40% for the implement phase.

## Changes in Detail

### New: `search_lex.clj`

Pure Clojure BM25 implementation — no JNI dependencies. Suitable for single-repo
indexing (thousands of files).

- `build-search-index` — builds inverted index with TF-IDF from repo-index files
- `search` — query → ranked hits with preview snippets (3 lines context, 5 max hits)
- Tokenization, IDF, BM25 scoring, context extraction all as small extracted functions

### Extended: existing files

| File | Change |
|------|--------|
| `factory.clj` | Added `->search-hit` factory |
| `schema.clj` | Added `Snippet` and `SearchHit` schemas |
| `interface.clj` | Added `build-search-index`, `search-lex`, `SearchHit`, `Snippet` exports |
| `implementer.edn` | Added Search Results context description |

### New: tool registration

- `components/tool-registry/resources/tools/repo/search-lex.edn` — registers
  `repo.search-lex` as a `:function` tool with query/max-results/context-lines params

## Testing Plan

- [x] 8 search tests: index building, basic search, schema conformance, relevance,
  max-results limit, snippet context, empty results, score ordering
- [x] All existing repo-index tests still pass
- [ ] Dogfood: run a work spec and verify search results improve file selection

## Related

- Iteration 1: PR #317 (scanner + repo map)
- Spec: N1-architecture.md §2.27–§2.30

## Checklist

- [x] BM25 search implementation
- [x] Factory function for SearchHit
- [x] Malli schemas for SearchHit and Snippet
- [x] Interface wired
- [x] Tool registration EDN
- [x] Implementer prompt updated
- [x] Tests passing
- [x] PR documentation
