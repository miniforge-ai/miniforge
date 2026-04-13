<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

<img src="miniforge_logo.png" width="30%" alt="Miniforge" id="logo">

# Miniforge

Write a spec. Get a pull request.

Miniforge is an autonomous software factory. You describe what you want — in
plain English or structured EDN — and miniforge plans the work, writes the code,
runs the tests, reviews itself, and opens a PR. No prompt engineering. No
copy-paste. Full SDLC.

## Before and After

```text
BEFORE                              AFTER
──────                              ─────
1. Write a ticket                   1. Write a spec
2. Create a branch                  2. mf run spec.edn
3. Read the codebase                3. Review the PR
4. Write code
5. Run tests
6. Fix failures
7. Lint
8. Fix lint
9. Commit
10. Push
11. Open PR
12. Write PR description
13. Wait for review
14. Address feedback
```

## How It Works

```text
Spec ──> Explore ──> Plan ──> Implement ──> Verify ──> Review ──> Release ──> PR
              │           │          │            │          │           │
              │           │          │            │          │           └─ creates branch,
              │           │          │            │          │              commits, pushes,
              │           │          │            │          │              opens PR
              │           │          │            │          │
              │           │          │            │          └─ self-reviews diff
              │           │          │            │             against spec + constraints
              │           │          │            │
              │           │          │            └─ runs gates: syntax, lint,
              │           │          │               no-secrets, tests-pass
              │           │          │
              │           │          └─ generates code via LLM agent
              │           │             inner loop: generate → validate → repair
              │           │
              │           └─ decomposes spec into a task DAG
              │              with dependencies and acceptance criteria
              │
              └─ scans the codebase, loads relevant files,
                 queries knowledge base
```

Each phase is driven by an autonomous agent backed by an LLM. Policy gates
govern every transition — bad code never ships. All decisions are traceable
via evidence bundles.

## Quickstart

### Prerequisites

- macOS or Linux
- [Babashka](https://github.com/babashka/babashka#installation) (`brew install babashka/brew/babashka`)
- An LLM backend: [Claude Code](https://claude.ai/claude-code) CLI,
  [Codex](https://openai.com/codex) CLI, or an API key (Anthropic/OpenAI)

### Install and Run

```bash
git clone https://github.com/miniforge-ai/miniforge.git
cd miniforge
bb bootstrap

# If using an API key (not needed if Claude Code or Codex CLI is installed)
# export ANTHROPIC_API_KEY="sk-ant-..."

# Run your first workflow
mf run examples/workflows/simple-refactor.edn
```

See the [Quickstart Guide](docs/quickstart.md) for a detailed walkthrough.

### Write a Spec

A spec is a description of what you want. Two fields are required:

```clojure
{:spec/title "Add input validation to the signup form"

 :spec/description
 "The signup form accepts any input without validation. Add server-side
  validation for email format, password strength (min 8 chars, 1 number),
  and username uniqueness. Return structured error messages."

 :spec/intent {:type :feature
               :scope ["src/auth/signup.clj"]}

 :spec/constraints
 ["No breaking changes to existing API"
  "All existing tests must pass"]

 :spec/acceptance-criteria
 ["Email validation rejects malformed addresses"
  "Password validation enforces minimum requirements"
  "Username uniqueness check queries the database"
  "Error messages are structured maps, not strings"]}
```

Specs can also be written as Markdown with YAML frontmatter. See
[Writing Specs](docs/user-guide/writing-specs.md).

### Run the Demo

```bash
# Watch miniforge improve itself (dogfooding demo)
bash examples/demo/run-demo.sh
```

See [Demo Guide](docs/demo.md) for a guided walkthrough.

## Workflow Types

| Workflow | Phases | Use For |
|----------|--------|---------|
| **Canonical SDLC** | explore, plan, implement, verify, review, release | Features, refactors, bug fixes |
| **Quick Fix** | implement, verify, done | Small, well-understood changes |

The workflow is selected automatically from the spec's intent, or overridden
with `:workflow/type :quick-fix`.

## Configuration

```bash
# LLM backend: auto-detected from installed CLIs
# Claude Code or Codex CLI are used automatically if installed.
# Otherwise, set an API key:
export ANTHROPIC_API_KEY="sk-ant-..."
# Or:
export OPENAI_API_KEY="sk-..."

# Tune execution
export MINIFORGE_MAX_ITERATIONS=50     # max phase retries
export MINIFORGE_MAX_TOKENS=150000     # token budget per workflow
```

See [Configuration Guide](docs/user-guide/configuration.md) for all options.

## Architecture

Miniforge is built on a governed workflow engine with pluggable phases, agents,
and policy packs.

```text
┌─────────────────────────────────────────────────────┐
│                    CLI / TUI / Web                   │
├─────────────────────────────────────────────────────┤
│              Workflow Engine (DAG Executor)           │
│  ┌──────┐ ┌──────┐ ┌───────┐ ┌──────┐ ┌─────────┐  │
│  │Explore│ │ Plan │ │Implmnt│ │Verify│ │ Review  │  │
│  └──────┘ └──────┘ └───────┘ └──────┘ └─────────┘  │
│              ↕           ↕         ↕                 │
│         ┌────────┐  ┌────────┐  ┌──────────┐        │
│         │ Agents │  │ Gates  │  │ Policies │        │
│         └────────┘  └────────┘  └──────────┘        │
├─────────────────────────────────────────────────────┤
│           LLM Backends (Claude, GPT, Local)          │
└─────────────────────────────────────────────────────┘
```

- **Agents**: Planner, Implementer, Tester, Reviewer, Releaser — each
  specialized for its phase
- **Gates**: Syntax, lint, no-secrets, tests-pass, coverage — policy enforcement
  at every transition
- **DAG Executor**: Plans decompose into task graphs; tasks run in parallel
  across isolated worktrees

See [Architecture Overview](docs/user-guide/architecture.md) for details.
See [Normative Specs](specs/normative/) for the full specification.

## Project Status

**Alpha** — actively developed, dogfooded daily. The pipeline (spec → PR) works
end-to-end for Clojure projects. Multi-language support, fleet orchestration,
and the web dashboard are in progress.

## Documentation

- [Quickstart](docs/quickstart.md) — Run your first workflow
- [Demo](docs/demo.md) — 5-minute guided demo
- [Writing Specs](docs/user-guide/writing-specs.md) — How to describe work
- [Phases](docs/user-guide/phases.md) — What the pipeline does
- [Configuration](docs/user-guide/configuration.md) — Tuning and backends
- [Architecture](docs/user-guide/architecture.md) — How it works
- [Contributing](CONTRIBUTING.md) — Development setup and guidelines

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, Polylith structure,
git conventions, and the pre-commit hook.

## License

[Apache License 2.0](LICENSE)
