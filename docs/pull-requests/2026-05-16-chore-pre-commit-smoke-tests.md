## Overview

Replaces `bb test` (stable-derived sweep) in the `bb pre-commit` chain
with a hand-curated `bb test:precommit` smoke set. Goal: drop the dev
loop's commit cost from "potentially hangs past 30 min" (PR #893's
recurring nightmare) to "13 namespaces, ~110 s wall, deterministic." CI
still runs `bb test:all` on every PR, so coverage is unchanged.

## Motivation

The recent dogfood/PR cycle showed two recurring failure modes in the
existing pre-commit:

- The stable-derived sweep can hang on a single bad test (PR #895
  bisected `workflow.runner-extended-test` after a 33-minute stall in
  the governed-mode Docker path).
- "Affected" projects can balloon when a load-bearing component
  changes, so a small fix routinely paid the full project-level test
  cost.

Both push agents toward `--no-verify`, which defeats the gate. A small,
fast, hand-picked smoke set is a better commitment: every line in the
allow-list is a deliberate choice, and CI still gates on the full
suite.

## Selection criteria

Documented inline in `resources/precommit-smoke-tests.edn`:

1. Load-bearing infrastructure — break this, half the codebase breaks
   (response, anomaly, schema, event-stream).
2. Cross-component boundary contracts — recent regressions pinned
   here: PR #867 (gate `:passed?`), PR #872/#894 (Anthropic property
   keys), PR #895 (`ReviewIssue` schema), PR #896 (resume terminal
   status).
3. Fast — sub-second per namespace, no Docker, no network.
4. Deterministic — never depends on worktree state.

Initial set (12 namespaces, ~250 tests):

- `ai.miniforge.response.interface-test`
- `ai.miniforge.anomaly.interface.schema-test`
- `ai.miniforge.event-stream.interface-test`
- `ai.miniforge.event-stream.core-test`
- `ai.miniforge.gate.interface-test`
- `ai.miniforge.gate.policy-test`
- `ai.miniforge.mcp-context-server.tools-test`
- `ai.miniforge.mcp-context-server.context-cache-test`
- `ai.miniforge.cli.main.commands.resume-test`
- `ai.miniforge.cli.workflow-runner.display-output-test`
- `ai.miniforge.agent.reviewer-test`
- `ai.miniforge.phase.interface-test`

**Deliberately excluded until PR #897 merges:**
`ai.miniforge.workflow.runner-test`. Its tests acquire a real
worktree at `System/getProperty "user.dir"` without the
fixture-level acquire mock (added in PR #897), and would commit
phase-completion messages into the active branch. The rogue commits
were caught during this PR's own pre-commit run — same shape as
[[feedback_runtime_state_never_git_tracked]]. Re-add in a follow-up
once #897 lands.

## Changes

- New `bb test:precommit` task in `bb.edn` that delegates to
  `test-runner/precommit-smoke`.
- `test-runner/precommit-smoke` loads
  `resources/precommit-smoke-tests.edn`, requires every namespace, and
  runs `clojure.test/run-tests` in one JVM invocation. Non-zero on any
  failure.
- `pre-commit` task's `:depends` flips `test` → `test:precommit`. The
  full suite (`bb test:all`, `bb test:since-stable`) remains
  unchanged and continues to be how CI runs.

## What CI still does

`.github/workflows/ci.yml` already runs `bb test:all` for every PR
(see the `Run tests` step in the Test job). Nothing changes for CI
coverage; only the pre-commit hook gets fast.

## Future optimization (not in this PR)

110 s is much faster than the prior 30+ min, but still longer than the
< 30 s target. The dominant cost is JVM startup per namespace cohort.
Candidates for a follow-up: a persistent test-runner REPL, or
filtering the smoke set by `git diff`-derived component touch.

## Test plan

- [x] `bb test:precommit` — 270 tests, 964 assertions, 0 failures,
  ~110 s wall.
- [ ] `bb pre-commit` on this branch — runs commit-budget, poly:check,
  lint:clj, fmt:md, test:precommit, test:graalvm. Expected green.

## Related

- PR #893 — first documented the `bb pre-commit` hang in
  `workflow.runner-extended-test`.
- PR #895 — fixed the hang via the dag-executor mock; this PR locks
  in the fast loop so we don't regress.
