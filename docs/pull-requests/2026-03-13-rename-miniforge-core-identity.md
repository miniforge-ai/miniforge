# rename: establish MiniForge Core / Miniforge / Data Foundry product identity

**Branch:** `rename/miniforge-core-identity`
**Date:** 2026-03-13
**Type:** Rename / Identity

## Summary

Establishes the three-layer product identity for the monorepo:

| Layer | Name | Role |
|-------|------|------|
| Engine/kernel | **MiniForge Core** | Governed workflow engine — DAG executor, phase registry, policy SDK, shared CLI base |
| Software factory | **Miniforge** | Autonomous SDLC product — multi-agent code generation, fleet management, PR lifecycle |
| ETL product | **Data Foundry** | Generic ETL product — data extraction, transformation, and loading workflows |

MiniForge Core is designed to be extracted and embedded in any product that
needs a governed workflow runtime (Miniforge, Data Foundry, Fleet, future
products).

## Changes

### Directory rename

- `projects/workflow-kernel/` -> `projects/miniforge-core/`

### Identity updates (19 files)

- **MiniForge Core identity** — app.edn name/display/description/home-dir
- **Miniforge product identity** — description updated to "Autonomous software factory — built on MiniForge Core"
- **Data Foundry identity** — ETL component README branded as Data Foundry
- **Polylith workspace** — project key and alias updated
- **Build/test infrastructure** — tasks/build.clj, tasks/test_runner.clj, bb.edn
- **Docs** — readme.md (full rewrite with products table), agents.md, CONTRIBUTING.md, N1-architecture.md
- **Release** — GitHub release body, Homebrew formula description
- **Resources** — default user config, policy pack, message catalog
- **Tests** — updated test values to match new names

### Deliberately unchanged

- **Namespaces** (`ai.miniforge.*`) — org-level identifier, not product
- **Env vars** (`MINIFORGE_*`) — breaking change, deferred
- **User paths** (`~/.miniforge/`) — breaking change, deferred
- **Copyright headers** — org identity (Miniforge.ai)
- **Historical PR docs** — archival record

## Test plan

- [x] All 1425 unit tests pass (0 failures, 0 errors)
- [x] Pre-commit hooks pass (lint, format, test, GraalVM compat)
- [x] Kernel project identity resolves correctly from new path
- [ ] `bb build:kernel` builds from `projects/miniforge-core/`
- [ ] `bb test:integration` runs kernel integration tests from new path
