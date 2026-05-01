<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# Pipeline Phases

Miniforge executes a sequential pipeline of phases. Each phase is driven by
an autonomous agent backed by an LLM, governed by policy gates.

## The Pipeline

```text
Explore ──> Plan ──> Implement ──> Verify ──> Review ──> Release ──> Observe
```

## Phase Details

### Explore

**Agent:** None (deterministic)
**Duration:** < 1 second

Scans the codebase and loads relevant files into context. No LLM call —
this is pure file I/O. Files are selected based on the spec's `:scope`
hint and the repo's structure.

The explore output is passed to all subsequent phases so agents don't
need to re-read the codebase.

### Plan

**Agent:** Planner
**Duration:** 1-3 minutes

Analyzes the spec and codebase to produce a task DAG (directed acyclic graph).
Each task has a description, type, dependencies, and acceptance criteria.

If the spec is already satisfied by existing code, the planner returns
`:already-satisfied` with evidence, and the pipeline skips to done.

For multi-task plans, tasks execute in parallel where dependencies allow.

### Implement

**Agent:** Implementer
**Duration:** 30 seconds - 5 minutes per task

Generates code based on the task description and codebase context. The agent
writes files directly to the execution environment.

**Inner loop:** After each generation pass, the code runs through validation
gates (syntax, lint, no-secrets). If a gate fails, the agent repairs and
retries automatically.

### Verify

**Agent:** None (gate execution)
**Duration:** 5-30 seconds

Runs the full verification suite: syntax checking, linting, secret detection,
and test execution. This is the final quality gate before review.

If verification fails and `:on-fail :implement` is configured, the pipeline
returns to the implement phase for another attempt.

### Review

**Agent:** Reviewer
**Duration:** 1-3 minutes

Self-reviews the diff against the original spec and constraints. The reviewer
checks for:

- Acceptance criteria satisfaction
- Constraint compliance
- Code quality and style
- Potential issues or risks

If the review finds issues and `:on-fail :implement` is configured, the
pipeline loops back for fixes.

### Release

**Agent:** Releaser
**Duration:** 30 seconds - 1 minute

Creates a git branch, commits all changes, pushes to the remote, and opens
a pull request. The PR includes:

- Title derived from the spec
- Body with implementation summary, file list, and test results
- A `docs/pull-requests/` documentation file

Requires a `GITHUB_TOKEN` for PR creation.

### Observe

**Agent:** None (monitoring loop)
**Duration:** Up to 72 hours (configurable)

Monitors the PR after creation:

- Polls for CI results and review comments
- Classifies comments (change request, question, approval, noise)
- Auto-fixes code based on reviewer feedback
- Auto-replies to questions
- Attempts merge when all gates pass

## Gates

Gates are policy checks that run at phase boundaries:

| Gate | What It Checks |
|------|---------------|
| `syntax` | Code parses without errors |
| `lint` | No linting violations (clj-kondo) |
| `no-secrets` | No API keys, passwords, or tokens in code |
| `tests-pass` | All tests pass |
| `coverage` | Test coverage meets threshold |

## Workflow Types

| Workflow | Phases | Use Case |
|----------|--------|----------|
| **canonical-sdlc** | All phases | Features, refactors, bug fixes |
| **quick-fix** | implement, verify, done | Small, well-understood fixes |

The workflow is auto-selected from the spec's intent or overridden with
`:workflow/type` in the spec.

## Failure and Retry

Each phase has a budget (max iterations, max tokens, max time). When a phase
fails:

1. If within budget, the phase retries with the error context
2. If `:on-fail` is set, the pipeline redirects to that phase
3. If budget is exhausted, the workflow fails with an evidence bundle

The implement → verify → review loop can iterate multiple times until all
gates pass or the budget is exhausted.
