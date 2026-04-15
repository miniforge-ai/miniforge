# Architecture Overview

Miniforge is built as a governed workflow engine with pluggable phases,
agents, and policy packs.

## Three Layers

```text
┌─────────────────────────────────────────────────────┐
│             Surface: CLI / TUI / Web                │
├─────────────────────────────────────────────────────┤
│          Software Factory: SDLC Workflows           │
│     Agents, PR Lifecycle, Fleet Management          │
├─────────────────────────────────────────────────────┤
│        MiniForge Core: Governed Workflow Engine      │
│    DAG Executor, Phase Registry, Policy SDK          │
└─────────────────────────────────────────────────────┘
```

**MiniForge Core** is the workflow engine — it executes DAGs of phases with
gates, budgets, and retry policies. It knows nothing about software development.

**Software Factory** adds SDLC-specific phases (plan, implement, verify, review,
release), agent implementations, and PR lifecycle management.

**Surface** provides CLI (`mf`), TUI (terminal dashboard), and web interfaces
for monitoring and control.

## Agent Model

Each pipeline phase is driven by a specialized agent:

| Phase | Agent | What It Does |
|-------|-------|-------------|
| Plan | Planner | Decomposes specs into task DAGs |
| Implement | Implementer | Generates code via LLM |
| Verify | — | Runs gates (no LLM needed) |
| Review | Reviewer | Self-reviews diffs |
| Release | Releaser | Generates commit/PR metadata |

Agents use LLMs (Claude, GPT, local models) but are governed by policy gates.
The agent doesn't decide if code is good enough — the gates do.

## DAG Executor

When a plan produces multiple tasks, they execute as a DAG:

```text
Plan output:
  Task A (no deps)     ─┐
  Task B (no deps)     ─┤──> parallel execution
  Task C (depends A,B) ─┘──> waits for A and B

Each task runs: implement → verify → review → release (own PR)
```

Tasks run in isolated git worktrees. Each task produces its own PR.
The DAG executor handles dependency ordering, parallelism (up to 4 tasks),
rate limit detection, and failure propagation.

## Policy Packs

Gates are organized into policy packs — composable sets of quality checks:

```text
Default gates:
  syntax     — code parses without errors
  lint       — no linting violations
  no-secrets — no credentials in code
  tests-pass — all tests pass
```

Policy packs are configured per-phase in the workflow definition. Custom
gates can be added via the gate registry.

## Component Model

Miniforge uses [Polylith](https://polylith.gitbook.io/polylith/) — a
component-based architecture where each component has:

- `src/` — implementation
- `test/` — tests
- `resources/` — config and data
- `interface.clj` — public API (the only file other components import)

There are 40+ components covering workflow execution, agent implementations,
policy enforcement, event streaming, and CLI/TUI/web surfaces.

See [CONTRIBUTING.md](../../CONTRIBUTING.md) for development details.

## Extension Points

- **Custom workflows** — define new phase sequences in EDN
- **Custom agents** — implement the agent protocol for new phase types
- **Custom gates** — register gate functions in the gate registry
- **Custom policy packs** — compose gates into named packs
- **LLM backends** — add new LLM providers via the backend protocol

## Further Reading

- [Normative Specs (N1-N11)](../../specs/normative/) — full technical specification
- [Design Documents](../design/) — architectural decisions and rationale
