<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Recover compliance refactor and green changed-brick tests

**Branch:** `codex/recover-compliance-refactor-tests`
**Base Branch:** `main`
**Recovered From:** `worktree-agent-ac127be3`
**Depends On:** none

## Overview

Recover the in-progress Claude work around the mixed security compliance
pipeline, related Polylith cleanup, and storage/test isolation work, then bring
 the changed-brick suite back to green so the branch can be reviewed as a PR.

## Motivation

The worktree contained a large staged and unstaged refactor spanning new
compliance components, workflow additions, interface cleanup, and execution
environment handling. Before it could be reviewed or merged, the branch needed:

- a durable commit instead of temporary worktree state
- the changed-brick test runner stabilized for linked-worktree git envs
- stale and broken tests repaired so `bb test` and the pre-commit hook pass

## Changes In Detail

### New Compliance Pipeline

- add `connector-sarif` for SARIF v2.1.0 and CSV ingestion
- add `gate-classification` for deterministic violation categorization
- add `workflow-security-compliance` with parse, trace, verify-docs,
  classify, and exclusion-generation phases
- add fixtures, e2e coverage, and a demo workflow entrypoint
- follow-up standards pass:
  - move workflow and gate defaults into EDN resources
  - route security-compliance phases through `phase.interface` instead of
    reaching into another component's internals
  - replace duplicated inline phase/test setup with small factory helpers
  - add localization resources for the compliance demo and classification gate

### Polylith And Interface Cleanup

- continue interface pass-through remediation across workflow, phase,
  control-plane, llm, repo-index, policy-pack, dag-executor, and related
  components
- continue phase/result movement and wiring needed for the broader
  Polylith compliance cleanup
- capture the associated recovery and remediation specs under `work/`

### Test And Runtime Fixes

- isolate Codex/Cursor MCP config writes to per-session roots so parallel tests
  do not fight over repo-local config files
- clean nested `mcp_servers.artifact.*` Codex config tables during session
  cleanup so newer Codex TOML layouts do not retain invalid orphaned sections
- preserve `:rule/severity` in connector-linter ETL output
- align verify and implement tests with the current default gate sets
- rewrite `phase.release-test` worktree setup so lint and parallel execution are
  stable
- group `workflow`, `phase`, `agent`, and `phase-software-factory` in one
  sequential changed-brick affinity bucket to avoid shared `with-redefs`
  collisions
- prevent `GIT_DIR` / `GIT_WORK_TREE` leakage into the test JVM so git commands
  inside temp repos resolve correctly
- restore planner behavior when no LLM backend is provided
- resolve workflow-security-compliance fixture paths from the classpath and
  improve stub trace API extraction so doc verification/classification tests
  reflect the intended sample data

## Testing Plan

- run `bb test`
- run pre-commit hook checks during `git commit`
- verify targeted namespaces while fixing regressions:
  - `ai.miniforge.phase.release-test`
  - `ai.miniforge.phase.verify-test`
  - `ai.miniforge.phase.artifact-persistence-test`
  - `ai.miniforge.workflow-security-compliance.phases-test`
  - `ai.miniforge.workflow-security-compliance.e2e-test`
  - `ai.miniforge.agent.planner-test`

## Deployment Plan

No deployment-specific rollout is required. This is codebase, workflow, and
test infrastructure work. Merge behind normal review once the PR is accepted.

## Related Issues/PRs

- `PR-552.md`
- `PR #556` — `codex/recover-compliance-refactor-tests`
- `work/polylith-compliance-remediation.spec.edn`
- `work/storage-root-configuration.spec.edn`

## Test Results

- `bb test` passed: `2386 tests, 13178 passes, 0 failures, 0 errors`
- pre-commit hook passed during commit, including:
  - staged Clojure lint
  - changed-brick tests
  - GraalVM/Babashka compatibility tests
- follow-up standards remediation also passed:
  - `bb pre-commit`
  - `2387 tests, 13186 passes, 0 failures, 0 errors`

## Checklist

- [x] Recover the in-progress work into a durable commit
- [x] Fix changed-brick test failures
- [x] Add PR documentation to the repo
- [x] Push branch
- [x] Open PR
