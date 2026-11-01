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

See `standards/miniforge/CLAUDE.md` for authoritative descriptions.

- **Stratified Design** — dependencies flow downward only; no cycles; pure Domain layer.
- **Simple Made Easy** — values over state; data over syntax; no speculative complexity.
- **PR Discipline** — one stratum per PR. Commits must stay below 200 reportable lines; whole PRs must stay below 600.
  Counts exclude blanks, comments, and generated paths, rather than using raw diff lines.
  `bb commit-budget` enforces commits through the pre-commit hook; `bb pr-budget` enforces PRs in CI.
  See the size rationale below. Branch from main; never bypass hooks.
- **Specification-Driven** — N-series specs are implementation contracts; code conforms to specs.
- **Adversarial Review Before Push** — read every changed file assuming something is wrong until verified.
  Passing clj-kondo and tests alone is insufficient.
  Apply this especially to generated and mechanical changes, including codemods, autofixers, and bulk renames.
  A supposedly behavior-neutral diff still requires verification.
  Copilot silently declines to comment past its own size threshold.
  A large PR that skips self-review can therefore receive no real review.
  The incident below explains this rule.

## PR Size and Review Discipline

Two gates were added on 2026-07-26:

- **`bb pr-budget`** runs in CI on `pull_request` events.
  It rejects a PR whose merge-base..head diff exceeds 600 reportable lines.
  It uses the same blank, comment, and generated-path exclusions as `bb commit-budget`.
  Commit-budget checks only one commit's staged diff.
  Before PR-budget, one large commit or several smaller commits could produce an unchecked oversized PR.
  An override requires `MINIFORGE_PR_BUDGET_OVERRIDE: <rationale>` in the PR description, visible to every reviewer.
  Commit-level overrides appear only in local shell history or CI logs.

  **Why 600, not 200:** the commit ceiling follows Smartbear 2010 and Cisco 2018 human review-throughput research.
  Equivalent published research does not exist for LLM-driven review.
  This codebase is overwhelmingly agent-written and agent-reviewed.
  The PR ceiling is three times the commit ceiling, allowing a small, coherent set of commits.
  It remains far below the roughly 10–12k raw lines where Copilot's review broke down.
  Treat 600 as calibrated, not fixed, just as with the commit ceiling.

- **Merge commits are exempt from `bb commit-budget`**, added 2026-08-07.
  A merge stages incoming code that its author did not write and whose original PR already counted it.
  For example, merging main failed the gate on 491 lines from someone else's reviewed code.
  Requiring an override for this trains people to use overrides routinely and dilutes their signal.
  `bb pr-budget` still covers the PR's own diff.
  Its merge-base..head comparison excludes content merged from the base.

  The same exemption covers stratum-lint, through `lint/stratum-staged`, added 2026-08-10.
  Both gates use `commit-budget/merge-in-progress?`.
  Incoming files can still be mid-way through a namespace-split wave and carry SL003 by design.
  Linting these files again blocked unrelated merges and encouraged warn-mode overrides.
  Their original PRs ran stratum-lint; CI checks the current PR's own diff.

- **Adversarial self-review before every push**, regardless of size.
  Read the actual diff skeptically, including code presented as behavior-neutral.

**Why both gates and review are needed:** the July 2026 stratum-lint remediation shipped PRs with 74–98 changed files
  and 10,000+ changed lines.
The work is tracked in `work/stratum-lint-baseline-2026-07-24.md`.
Each PR used a commit-budget override.
The commit-only gate permitted this; no PR-size gate existed.

Copilot declined to post comments on the largest PRs.
It reviewed a 9,906-line diff but declined a 12,592-line diff.
The review threshold lies somewhere between those sizes and is undocumented by GitHub.
Dedicated adversarial reviews ran after several PRs had merged, costing 170–210k tokens per PR and requiring follow-ups.

Those reviews found a comment-attachment bug, an undisclosed behavior change, and stale or contradictory comments.
Lint and tests had not caught them.
PR-budget requires oversized work to be split or explicitly flagged with a reviewable rationale.
Adversarial review remains necessary because green CI alone cannot establish correctness.

## Writing Spec Task Descriptions (`work/*.spec.edn`)

Task descriptions in `work/` specs are agents' primary context.
Stale descriptions cause agents to reimplement completed work, leading to syntax errors and broken commits.

**Rules:**

- **Never use line numbers**, such as "In foo (line 123)." Lines shift with every edit.
  Use function and variable names instead.
- **Reference function and variable names** so agents can locate code through search.
- **Describe WHAT to implement and WHY**, not WHERE in the file.
- **Keep scope small** — one acceptance criterion per task.
- **Remove completed tasks.**
  Leaving them in the spec can cause agents to reimplement them.
  Remove the task instead of expecting every agent to recognize that it is done.

Bad: `In enter-verify (line 116): remove artifact retrieval (lines 139-148).`
Good: `In enter-verify: remove artifact retrieval from [:execution/phase-results :implement].`

## Container Runtime (N11-delta)

In governed mode (`--execution-mode governed`) miniforge runs every task
inside an OCI container; the default local mode runs it in a host git
worktree, unisolated. The runtime adapter
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
