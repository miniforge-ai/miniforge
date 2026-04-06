<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Miniforge Agent Knowledge Base

This document is the entry point for AI agents working in this repository.
Shared engineering standards live in `.standards/` (git submodule →
`miniforge-ai/miniforge-standards`). Consult `.standards/agents.md` for the
universal rule catalog; this file covers miniforge-specific product knowledge.

## Product Layers

This repo houses three product layers on one Polylith workspace:

- **MiniForge Core** — governed workflow engine (shared kernel)
- **Miniforge** — autonomous software factory (SDLC product)
- **Data Foundry** — ETL product (data extraction, transformation, loading)

## Standards Quick Reference

See `.standards/agents.md` for the full catalog. Key rules:

| Need to... | Rule |
|------------|------|
| Understand architecture | `.standards/foundations/stratified-design` |
| Write Clojure code | `.standards/languages/clojure` |
| Work with Polylith | `.standards/frameworks/polylith` |
| Create a branch | `.standards/workflows/git-branch-management` |
| **Commit code** | **`.standards/workflows/pre-commit-discipline`** (CRITICAL) |
| Plan a PR | `.standards/workflows/pr-layering` |
| Document a PR | `.standards/workflows/pr-documentation` |

## Miniforge Project Structure

```text
miniforge/
├── .standards/            # git submodule — shared engineering standards
├── bases/                 # Polylith bases (entry points)
├── components/            # Polylith components (domain logic)
│   ├── agent/             # Agent implementations (implementer, reviewer, etc.)
│   ├── phase/             # Shared phase lifecycle & telemetry
│   ├── phase-software-factory/  # Software factory phases (plan→implement→review→release)
│   ├── event-stream/      # In-process event pub/sub
│   ├── knowledge/         # Knowledge store & rule loader
│   ├── llm/               # LLM client + model catalog
│   ├── web-dashboard/     # Dashboard server (HTTP + WebSocket)
│   └── ...
├── projects/              # Deployable artifacts
│   ├── miniforge/         # Miniforge (software factory)
│   ├── miniforge-core/    # MiniForge Core (engine-only)
│   └── miniforge-tui/     # Terminal UI
├── development/           # REPL & dev tooling
├── docs/
│   └── pull-requests/     # PR documentation (one file per feature branch)
├── bb.edn                 # Babashka tasks (pre-commit, linting, tests)
├── deps.edn               # Clojure dependencies
└── workspace.edn          # Polylith workspace config
```

## Babashka Tasks

```bash
bb tasks              # List all tasks
bb pre-commit         # Run lint + format + test
bb lint:clj           # Lint staged Clojure files
bb fmt:md             # Format staged Markdown
bb test               # Run poly test
bb hooks:install      # Install pre-commit hook
```

## Miniforge-Specific Rules

| Dewey | File | Description |
|-------|------|-------------|
| 810 | `.standards/project/header-copyright` | Apache 2.0 header on all Clojure sources |

## Core Principles (Always Apply)

See `.standards/CLAUDE.md` for authoritative descriptions. Summary:

- **Stratified Design** — dependencies flow downward only; no cycles; pure Domain layer
- **Simple Made Easy** — values over state; data over syntax; no speculative complexity
- **PR Discipline** — one stratum per PR, <400 lines, branch from main, never bypass hooks
- **Specification-Driven** — N-series specs are implementation contracts; code conforms to specs

## Writing Spec Task Descriptions (`work/*.spec.edn`)

Task descriptions in `work/` specs are the primary context given to agents.
Stale descriptions cause agents to misidentify what's done and try to re-implement
existing work — which leads to syntax errors and broken commits.

**Rules:**

- **Never use line numbers** (e.g., "In foo (line 123)"). Lines shift with every edit.
  Use function/variable names instead: "In the `foo` function".
- **Reference function and variable names** — these survive refactors far better.
  Agents use grep/read tools to locate code by name, not by line.
- **Describe WHAT to implement and WHY**, not WHERE in the file.
- **Keep scope small** — one acceptance criterion per task where possible.
- **Remove tasks that are already done** rather than leaving them with stale descriptions.
  Agents that find "already implemented" tasks may still try to change things; removing
  the task from the spec is cleaner than hoping the agent's judgment holds.

Bad: `In enter-verify (line 116): remove artifact retrieval (lines 139-148).`
Good: `In the enter-verify function: remove artifact retrieval from [:execution/phase-results :implement].`
