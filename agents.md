<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Miniforge Agent Knowledge Base

This document is the entry point for AI agents working in this repository.
Shared engineering standards live in `standards/miniforge/` (git submodule →
`miniforge-ai/miniforge-standards`). Consult `standards/miniforge/agents.md` for the
universal rule catalog; this file covers miniforge-specific product knowledge.

## Product Layers

This repo houses three product layers on one Polylith workspace:

- **MiniForge Core** — governed workflow engine (shared kernel)
- **Miniforge** — autonomous software factory (SDLC product)
- **Data Foundry** — ETL product (data extraction, transformation, loading)

## Standards Quick Reference

See `standards/miniforge/agents.md` for the full catalog. Key rules:

| Need to... | Rule |
|------------|------|
| Understand architecture | `standards/miniforge/foundations/stratified-design` |
| Write Clojure code | `standards/miniforge/languages/clojure` |
| Work with Polylith | `standards/miniforge/frameworks/polylith` |
| Create a branch | `standards/miniforge/workflows/git-branch-management` |
| **Commit code** | **`standards/miniforge/workflows/pre-commit-discipline`** (CRITICAL) |
| Plan a PR | `standards/miniforge/workflows/pr-layering` |
| Document a PR | `standards/miniforge/workflows/pr-documentation` |

## Miniforge Project Structure

```text
miniforge/
├── standards/
│   └── miniforge/   # git submodule — shared engineering standards
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
| 810 | `standards/miniforge/project/header-copyright` | Apache 2.0 header on all Clojure sources |

## Core Principles (Always Apply)

See `standards/miniforge/CLAUDE.md` for authoritative descriptions. Summary:

- **Stratified Design** — dependencies flow downward only; no cycles; pure Domain layer
- **Simple Made Easy** — values over state; data over syntax; no speculative complexity
- **PR Discipline** — one stratum per PR, <200 lines (enforced in CI by
  `bb pr-budget`, mirroring the commit-time `bb commit-budget` gate),
  branch from main, never bypass hooks
- **Specification-Driven** — N-series specs are implementation contracts; code conforms to specs
- **Adversarial Review Before Push** — before opening or pushing to any
  PR, review your own diff adversarially: read every changed file
  assuming something is wrong until you've verified it isn't, not just
  clj-kondo/tests passing. This applies doubly to mechanical/generated
  diffs (codemods, autofixers, bulk renames) — a diff being "supposed
  to be" behavior-neutral is a claim to verify, not a fact to assume.
  GitHub Copilot's automated review silently declines to comment at
  all past its own size threshold, so a large PR that skips this step
  may receive zero real review. See `## PR Size and Review Discipline`
  below for the incident that motivated this.

## PR Size and Review Discipline

Two related, previously-missing gates, added 2026-07-26:

- **`bb pr-budget`** (CI, `pull_request` events only): rejects a PR
  whose whole merge-base..head diff exceeds 200 reportable lines
  (same blank/comment/generated-path exclusions as `bb commit-budget`).
  This is distinct from `commit-budget`, which only ever looked at one
  commit's staged diff — a PR made of a single large commit (or several
  commits each individually under budget) sailed through unchecked
  before this existed. Override via a `MINIFORGE_PR_BUDGET_OVERRIDE:
  <rationale>` line in the PR description, visible to every reviewer
  on the PR (unlike the commit-level env-var override, visible only in
  local shell history / CI logs).
- **Adversarial self-review before every push**, not just for PRs that
  trip the size gate. Read the actual diff like a skeptical reviewer,
  not the tool's own "this should be safe" framing.

**Why both, together:** a mechanical stratum-lint autofix program
(2026-07 Wave 1 remediation, tracked in
`work/stratum-lint-baseline-2026-07-24.md`) shipped several PRs of
74-98 changed files and 10,000+ changed lines each, each as one
`MINIFORGE_COMMIT_BUDGET_OVERRIDE`'d commit — legal under the
commit-only gate that existed at the time. GitHub Copilot's automated
review silently declined to post any comments at all on the largest of
these (past its own undocumented size ceiling), so the only real
review those PRs got was a dedicated adversarial pass run by hand,
*after* several had already merged. That pass found real defects
(a comment-attachment bug, an undisclosed behavior change buried
inside a PR whose description claimed "mechanical, no logic changes,"
several stale/contradictory comments) that plain CI (lint + tests
green) had not and could not catch. `pr-budget` exists so a PR that
size can't reach merge without either being split up or explicitly
flagging its own size with a reviewable rationale; the adversarial-review
principle exists because passing CI was never sufficient evidence of
correctness for a mechanical diff this large, and treating it as
sufficient is what let the gap open in the first place.

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

## Container Runtime (N11-delta)

Miniforge runs every task inside an OCI container. The runtime adapter
(see `components/dag-executor/src/.../runtime/`) treats Docker, Podman,
and nerdctl as interchangeable providers of the same OCI surface.

**Rules for agents:**

- **Do not branch on `:runtime/kind` in code.** Per-runtime differences
  (CLI dialect, capabilities, container defaults) live in
  `runtime/registry.edn`. If a runtime needs a different flag than
  Docker, declare an override in its `:flags` map; do not add a
  `(case kind ...)` in the executor.
- **Do not introduce daemon-API integration.** The executor shells out
  via the resolved runtime CLI. Adding HTTP-API code re-couples
  miniforge to one runtime.
- **Image references must be fully qualified** (`docker.io/<repo>:<tag>`
  or `@sha256:<digest>`). Short-name references behave differently
  across runtimes; Podman's first-run prompt is a recurring footgun.
  See `runtime/images.edn` for the live default set.
- **Selection algorithm is settled** — explicit `:runtime-kind` config
  wins, then auto-probe `[:podman :docker :nerdctl]`. Never silently
  fall back from an explicit kind.
