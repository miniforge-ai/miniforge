# Miniforge Agent Knowledge Base

This document serves as the entry point for AI agents working in this repository. 
It provides quick access to all rules, conventions, and expert knowledge encoded in the system.

## Quick Reference

| Need to... | Consult |
|------------|---------|
| Understand architecture | `001-stratified-design`, `010-simple-made-easy` |
| Write Clojure code | `210-clojure` |
| Write Python code | `220-python` |
| Work with Kubernetes | `320-kubernetes` |
| Create a branch | `710-git-branch-management` |
| Plan a PR | `720-pr-layering` |
| Document a PR | `721-pr-documentation` |
| Create a new rule | `900-rule-format` |

## Rules Catalog (Dewey Classification)

Rules are organized in `.cursor/rules/` with Dewey-style folders and numeric prefixes.

```zsh
.cursor/rules/
├── 000-index.mdc                    # Master catalog
├── 000-foundations/                 # Architecture & philosophy
│   ├── 001-stratified-design.mdc
│   └── 010-simple-made-easy.mdc
├── 200-languages/                   # Language conventions
│   ├── 210-clojure.mdc
│   └── 220-python.mdc
├── 300-frameworks/                  # Platform rules
│   └── 320-kubernetes.mdc
├── 700-workflows/                   # Process rules
│   ├── 710-git-branch-management.mdc
│   ├── 720-pr-layering.mdc
│   └── 721-pr-documentation.mdc
└── 900-meta/                        # Rule templates
    └── 900-rule-format.mdc
```

### 000-foundations/ — Core Principles

| ID | File | Description |
|----|------|-------------|
| 001 | `001-stratified-design` | One-way DAG dependencies; Foundations → Domain → Application → Adapters → Infrastructure |
| 010 | `010-simple-made-easy` | Choose simple (uncomplected) over easy (familiar); values over state; data over syntax |

### 200-languages/ — Language Rules

| ID | File | Description |
|----|------|-------------|
| 210 | `210-clojure` | Polylith workspace; cross-component deps via `.interface`; per-file stratified layers (max 3) |
| 220 | `220-python` | Poetry + pyproject.toml; functional style; type hints |

### 300-frameworks/ — Platforms

| ID | File | Description |
|----|------|-------------|
| 320 | `320-kubernetes` | Kustomize base/overlays; ArgoCD app-of-apps; minimal patches; context switching |

### 700-workflows/ — Processes

| ID | File | Description |
|----|------|-------------|
| 710 | `710-git-branch-management` | Always branch from main; verify before branching; single-purpose PRs |
| 720 | `720-pr-layering` | Decompose PRs by stratum; merge bottom-up; <400 lines per PR |
| 721 | `721-pr-documentation` | Create `docs/pull-requests/YYYY-MM-DD-branch.md` for each feature branch |

### 900-meta/ — Templates

| ID | File | Description |
|----|------|-------------|
| 900 | `900-rule-format` | Template for creating rules; Dewey classification reference |

## Dewey Classification Quick Reference

```
000-099  Foundations     Architecture, design philosophy
100-199  Tools           Linters, formatters, build tools
200-299  Languages       Clojure, Python, JS/TS, Go, Rust
300-399  Frameworks      K8s, web frameworks, cloud, databases
400-499  Testing         Unit, integration, E2E, code review
500-599  Operations      CI/CD, monitoring, security
600-699  Documentation   API docs, architecture docs
700-799  Workflows       Git, PRs, releases
800-899  Project         Reserved for project-specific
900-999  Meta            Templates, indexes
```

## Core Principles (Always Apply)

### Stratified Design

- Dependencies flow **downward only**: Adapters → Application → Domain → Foundations
- No cycles in the import graph
- Pure core (Domain layer has no I/O)

### Simple Made Easy

- Prefer **simple** (unbraided) over **easy** (familiar)
- Values over state; data over syntax; functions over methods
- Centralize policy as data, not scattered conditionals

### PR Discipline

- Each PR = one stratum, <400 lines, independently mergeable
- Branch from main (never from feature branches unless explicit)
- Document PRs in `docs/pull-requests/`

## Project Structure

```
miniforge/
├── .cursor/rules/     # AI agent rules (this knowledge base)
├── bases/             # Polylith bases (entry points)
├── components/        # Polylith components (domain logic)
├── projects/          # Deployable artifacts
├── development/       # REPL & dev tooling
├── docs/              # Documentation
│   └── pull-requests/ # PR documentation
├── bb.edn             # Babashka tasks (pre-commit, linting, tests)
├── deps.edn           # Clojure dependencies
└── workspace.edn      # Polylith workspace config
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

## For Agents: How to Use This Knowledge Base

1. **Before writing code**: Check relevant rules (language, framework, architecture)
2. **Before creating branches**: Consult `710-git-branch-management`
3. **Before opening PRs**: Follow `720-pr-layering` and `721-pr-documentation`
4. **When creating rules**: Follow `900-rule-format` with Dewey numbering
5. **When in doubt**: Apply `001-stratified-design` and `010-simple-made-easy`
