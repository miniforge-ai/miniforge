<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# test: keep the suite off the developer's checkout, home, and codex

## Overview

Two test-hygiene defects observed on the trap bench, series 8 (2026-09-03):

1. With `MINIFORGE_CODEX_PATH` exported, two release-phase tests fail
   because the release executor context appends the codex consultation
   to `:task/behavior-addendum` and the tests had stubbed only the
   standards pack.
2. `bb test` inside a linked worktree left 108 `task-<8hex>` branches on
   the enclosing repository within a minute. The developer checkout here
   carried 38,253 of them, and `~/.miniforge/checkpoints` held 187k run
   directories, most of them one `runner-test-done.edn` phase file.

Both are fixed at the test boundary. Production code is unchanged.

## Defect 1 — codex read from the environment

`codex-pin/configured-codex-dir` reads `MINIFORGE_CODEX_PATH`. `release`'s
`build-executor-context` calls `codex-pin/landings-outcome :release` through
the two-arity form, so the environment decides whether codex text lands in
the addendum. `release-threads-behavior-addendum-onto-executor-context-test`
and `release-omits-behavior-addendum-when-filter-returns-nil-test` assert
the addendum is exactly the stubbed pack text, or absent.

Reproduced by pointing `MINIFORGE_CODEX_PATH` at a copy of
`components/codex/test-resources/codex-fixture` with the four phase
situations added: exactly those two tests fail, nothing else in the brick.

Fix: `release-test` gains a `with-unconfigured-codex` fixture that rebinds
`configured-codex-dir` to nil for every test, composed into the existing
`:each` registration (`use-fixtures :each` replaces earlier registrations,
so a second call would have been silently dropped). The consultation
contract itself is covered by `codex-pin-test` with explicit directories.

The alternative — threading the codex directory through the execution
context with the environment read only at the CLI boundary — was not taken
here. Five call sites and every entry path that builds a context (CLI run,
resume, DAG sub-workflows, the MCP server) would have to agree, and a path
that missed the key would turn the capability off silently.

## Defect 2 — pipelines acquire worktrees from "."

`runner/run-pipeline` defaults `:repo-path` to `"."` and, in `:local`
mode, `acquire-execution-environment!` builds a real worktree executor and
runs `git worktree add -b task-<8hex>` against whatever repository the test
JVM was launched in. `release-environment!` removes the worktree directory;
the branch stays. Checkpoints resolve to `~/.miniforge/checkpoints` unless
the run names `:checkpoint/root`, and persisted task bundles go to the same
root.

Measured before the fix, one namespace at a time, in this worktree:

| Suite | New `task-*` branches | New checkpoint dirs |
|---|---|---|
| `brick:workflow` | 0 | 117 |
| `brick:dag-executor` | 0 | 0 (mocks git) |
| `runner-integration-test` (project level) | 2 | 20 |
| `meta-agent-test` (project integration) | 3+ | — |

The `dag-executor` unit tests all stub `run-git`; the producer is every
namespace that calls `run-pipeline` without stubbing acquisition — the
project-level tests, plus the DAG orchestrator tests whose sub-workflows
acquire once per task.

Fix: a new `ai.miniforge.workflow.isolation-test-support/with-isolated-host`
fixture (workflow brick test dir) that, for the duration of a namespace,
redirects every sink into one throwaway tree:

- `:repo-path` `"."` or absent → a fresh `git init` host repository with one
  commit on `main` (the branch `run-pipeline` acquires from)
- worktree base path and bundle archive → under the tree, via the
  `registry-config-for-mode` seam (`:local` mode ignores `:executor-config`,
  so the worktree entry is merged there)
- `checkpoint-store-paths/default-checkpoint-root` → under the tree

An explicit non-`"."` repo-path and an explicit `:checkpoint/root` still win.
The tree is deleted on unwind. Registered as a `:once` fixture in the 13
namespaces that run pipelines: eight in `components/workflow/test`, three in
`projects/miniforge/test`, `projects/miniforge/integration`'s
`meta-agent-test`, and the e2e namespace.

`host-git-fixtures` in `dag-executor` was the model for the throwaway
repository. It is not required across bricks: a workflow test depending on
a dag-executor non-interface namespace is the shape Polylith's dependency
check refuses, and the fixture needs six lines of git.

## Verification

- `brick:phase-software-factory` with `MINIFORGE_CODEX_PATH` set: 488 tests,
  0 failures (was 2).
- `brick:workflow` after the fixture: 1894 tests, 0 failures. The run named
  21 `task-*` branches in its logs; none exist in this repository.
- Project-level namespaces (`runner-integration-test`,
  `dag-orchestrator-test`, `opsv-lifecycle-integration-test`,
  `meta-agent-test`) run directly: 13 branches named, none exist in this
  repository, no new worktree directories under `~/.miniforge/worktrees`.
- Other sessions were running the suite concurrently on this machine during
  measurement; their runs kept adding branches and checkpoint directories
  (bursts of 18 dirs in one second, none of whose ids appear in my logs).
  Attribution therefore used the branch names each run logged, not
  directory counts.

## Hook environment

The first commit of this branch failed its pre-commit smoke run, and the
launch repository came out of it with `core.bare = true` in its shared
`.git/config`. Mechanism: `git commit` exports `GIT_DIR` and
`GIT_INDEX_FILE` to its hooks, the smoke runner passed them through to the
test JVM, and the fixture's `git init` in a temp directory therefore
re-initialised the hook's repository — with no work tree in sight, as bare.
`bb test` (the stable-derived runner) already strips those four variables
before spawning `poly test`; the smoke runner did not.

Two fixes: the fixture runs every git command with `GIT_INDEX_FILE`,
`GIT_DIR`, `GIT_WORK_TREE`, and `GIT_COMMON_DIR` removed from its
environment, so it is hermetic whoever launches it; and `precommit-smoke`
now spawns the test JVM through the same sanitizer `bb test` uses, so
production git calls reached from a smoke namespace cannot act on the hook's
repository either. Repair for a checkout that already carries the damage:
`git config core.bare false` in that checkout.

## Stratum lint

`release_test.clj` already exceeds the SL003 layer budget at `main` (five
strata; the limit is three). Touching it trips the block, so this commit ran
with `MINIFORGE_STRATUM_BUDGET_MODE=warn`. Splitting that namespace is not
in scope. The mechanical stratum rewrite of the other ten namespaces landed
in the preceding PR (`2026-09-04-fix-stratum-lint-wave-workflow-pipeline-tests`).

## Not in this PR

- `meta-agent-test` (project integration) fails 13 assertions with and
  without this change when run directly from `projects/miniforge`: every
  pipeline finishes `:failed`. Pre-existing; not investigated here.
- Checkpoint writers outside `run-pipeline` (`checkpoint-store-test`,
  `dag-resilience-resume-test`) were not audited. The open chip about tests
  writing to the real checkpoint root is narrowed, not closed.
- The 38k `task-*` branches already on the developer checkout, and the
  187k checkpoint directories, are not deleted. Only the branches this
  session's own unfixtured runs created were removed.
- Whether other bricks' tests create environments against the launch
  repository was not measured beyond `workflow`, `dag-executor`, and the
  project-level namespaces.
