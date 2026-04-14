<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Add repo-index component with scanner and repo map

## Overview

Adds a new `components/repo-index/` component that replaces blind file loading with a
git-backed repo scanner and token-budgeted repo map. This is Iteration 1 of the Repo
Indexer plan (N1 spec §2.27–§2.30), delivering the scanner + repo map + implement-phase
wiring.

## Motivation

The implement phase currently uses `file_context.clj` to blindly load up to 25 files
(500 lines each) into agent context — roughly 15,000 tokens of raw code per invocation.
Agents can't search for what they need; they get whatever the orchestrator guesses.

This PR replaces that with:

1. A **repo scanner** (`git ls-tree -r --long HEAD`) that indexes every tracked file
   with blob-sha, size, line count, language, and generated-file detection
2. A **repo map generator** that produces a compact, token-budgeted table of contents
   grouped by directory
3. **Implement phase wiring** that gives agents the repo map alongside files-in-scope,
   so they understand the repo structure before generating code

Expected token savings: ~15,000 tokens → ~500 token repo map + only requested files.

## Changes in Detail

### New: `components/repo-index/`

| File | Lines | Purpose |
|------|-------|---------|
| `deps.edn` | 5 | Component deps (logging only) |
| `schema.clj` | 70 | Malli schemas: FileRecord, RepoIndex, RepoMapEntry, RepoMapSlice |
| `scanner.clj` | 160 | Walk `git ls-tree`, detect language (extension map), detect generated files (glob patterns) |
| `repo_map.clj` | 130 | Token-budgeted repo map — groups by directory, incremental text + budget enforcement |
| `storage.clj` | 65 | Persist/load index to `.miniforge/index/{tree-sha}.edn` |
| `interface.clj` | 120 | Public API: `build-index`, `repo-map`, `repo-map-text`, `get-file`, `get-files`, `find-files`, `files-by-language` |
| `interface_test.clj` | 80 | 9 tests, 1443 assertions (self-test against miniforge repo) |

### Modified: `components/phase-software-factory/`

- **`deps.edn`**: Added `repo-index` dependency
- **`implement.clj`**: Added `build-repo-map-context` helper; `build-implement-task` now
  builds a repo index, uses it for file loading when available, and passes `:task/repo-map`
  and `:task/repo-index` to the agent task

### Modified: `components/agent/`

- **`implementer.clj`**: Added `format-repo-map` function; user prompt now includes the
  repo map before existing files
- **`resources/prompts/implementer.edn`**: System prompt updated to describe the
  Repository Map in the input format section

### Modified: Root `deps.edn`

- Added `repo-index` to `:dev` (src + resources) and `:test` (test + extra-deps) aliases

## Design Decisions

- **Scanner uses `git ls-tree -r --long HEAD`** — fast, already content-addressed, no disk walks
- **Language detection**: extension-based map (50+ extensions), no tree-sitter dependency yet
- **Generated file detection**: regex patterns for vendor/, node_modules/, *.min.js, lock files, etc.
- **Repo map format**: markdown table grouped by top-level directory, with configurable token budget
  (default 500 tokens)
- **Index storage**: EDN at `.miniforge/index/{tree-sha}.edn` — keyed by tree-sha for cache hits
- **Graceful fallback**: if scanning fails, implement phase falls back to existing `file_context.clj`

## Measured Results (miniforge self-test)

| Metric | Value |
|--------|-------|
| Files indexed | 1,173 |
| Total lines | 253,506 |
| Languages detected | 8 (clojure 705, edn 203, markdown 184, ...) |
| 500-token budget | 30 files shown |
| 1,000-token budget | 55 files shown |
| 2,000-token budget | 102 files shown |

## Testing Plan

- [x] 9 unit tests pass (1,443 assertions) — schema validation, scanner, repo map, budget enforcement, file retrieval
- [x] `implement.clj` compiles and loads cleanly
- [x] `implementer.clj` compiles and loads cleanly
- [ ] Dogfood: run a work spec and compare token usage before/after
- [ ] Verify agent output quality with repo map context

## Deployment Plan

Merge to main. No infrastructure changes. The `.miniforge/index/` directory should be
added to `.gitignore` if not already present.

## What's Next (Iteration 2)

BM25 lexical search (`search_lex.clj`) + `repo.search-lex` tool registration, so agents
can search the codebase by keyword instead of relying solely on files-in-scope.

## Related Issues/PRs

- Spec: N1-architecture.md §2.27–§2.30
- Informative: `specs/informative/repo-context-assembly-schemas.edn`
- Prior: PR #247 (normative spec additions)

## Checklist

- [x] New component scaffold (`components/repo-index/`)
- [x] Scanner + schema + repo map + storage
- [x] Public interface with Polylith conventions
- [x] Root deps.edn registration (dev + test)
- [x] Implement phase wiring
- [x] Implementer prompt updates
- [x] Tests passing
- [x] PR documentation
