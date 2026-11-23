<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix: Cut a spec run's task worktree from the spec's repository

## Overview

`mf run <spec>` now cuts the task worktree, and its task branch, from the git repository that holds the spec
file. Before, it cut them from the repository of the process's working directory, even when the spec lived in
another repository.

## Motivation

Found during the #2377 cancel acceptance rerun (gap 4 in the comment on issue #2377). The first attempt ran
`bb miniforge run <disposable-repo>/spec.edn --worktree <disposable-repo>` from a miniforge checkout. The run
banner printed the disposable repository as Source and Worktree. The agent ran in
`<MINIFORGE_HOME>/worktrees/task-<id>`, a worktree of miniforge: the `task-<id>` branch was created in
miniforge's `.git`, and the spec's files were absent. The rerun worked around it by starting the CLI from
inside the disposable repository with `bb --config <checkout>/bb.edn`.

Cause:

1. `workflow/runner_preparation.clj` `acquire-environment` reads `:repo-path` from the run options and
   defaults to `"."`. The worktree executor runs `git -C <repo-path> worktree add`, so `"."` is the process's
   working directory.
2. `run-workflow-from-spec!` passed `:repo-url`, `:branch` and `:execution-mode`, but no `:repo-path`.
3. The CLI already knew the spec's repository: `create-workflow-context` computes `:source-root` from
   `:spec/source-dir`, and `assert-source-dir-alignment!` checks against it. Nothing passed it on.
4. The default base branch had the same split. `decorate-spec-with-runtime-context` read `:git-branch` from
   the working directory's checkout, and `sandbox/infer-branch` used it. In another repository that branch
   usually does not exist, so `git worktree add -b` failed and the executor fell back to a detached HEAD with
   no task branch.

## Changes in Detail

1. `bases/cli/.../workflow_runner.clj` `run-workflow-from-spec!`: the run context carries
   `:repo-path (:source-root ctx)`, the spec's repository root.
2. `bases/cli/.../workflow_runner/context.clj` `decorate-spec-with-runtime-context`: `:git-branch` and
   `:git-commit` come from the spec's source dir when the spec has one, else from the working directory as
   before. The task worktree is cut from that branch unless the spec sets `:spec/branch`.
3. New integration test `projects/miniforge/test/.../spec_repo_worktree_integration_test.clj`.

### Why `--worktree` does not override `:repo-path`

`--worktree` is the execution worktree. The backend preflight runs in it, and the banner prints it as
Worktree. The run works there only if no task worktree can be acquired. #766 split it from
`:spec/source-dir` on purpose. Using it as the repository to cut from adds nothing and can reintroduce this
bug:

1. If `--worktree` is a worktree of the spec's repository, it shares that repository's git common dir.
   `git worktree add` from either path then creates the same branch in the same repository. The base branch
   comes from branch inference, not from `--worktree`.
2. If `--worktree` is in another repository, cutting from it is this bug again.
3. The observed run passed `--worktree` equal to the spec's repository and still failed, because nothing
   passed either path to the runner.

### Behavior change for same-repository runs

Take a spec inside a linked worktree, for example `.claude/worktrees/<name>/work/x.spec.edn`, run from the
main checkout. Its task is now cut from that linked worktree's branch, not the main checkout's branch. The banner
already printed that worktree and branch as Source and Branch. A spec in the working directory's own checkout,
including `bb dogfood`, resolves to the same root and branch as before.

## Testing Plan

1. `ai.miniforge.cli.workflow-runner.spec-repo-worktree-integration-test` runs `run-workflow-from-spec!`
   through the real CLI context, runner and worktree executor. The spec is in one temp repository. A second
   temp repository stands in for the working directory: a `"."` repo path is sent there, so the developer
   checkout is never written to. The miniforge home is a temp dir. A probe phase records the git common dir,
   branch and files of the worktree it ran in.
2. On main the test fails 5 of 6 assertions. The worktree belongs to the stand-in repository, is detached
   and lacks the spec file, and the task branch is in the stand-in repository. With this change all pass
   (about 4.5 s, hence a project integration test).
3. 21 CLI test namespaces covering the changed functions (run, context, sandbox, identity, operator, manifest,
   kanban and related) pass: 95 tests, 282 assertions.
4. After the runs, no `task-*` ref was created in the developer checkout and nothing was written under
   `~/.miniforge`.
5. `context-test` pins the branch lookup on its own. With `get-git-info` stubbed per directory, a spec with a
   source dir gets that repository's branch and commit. A spec without one gets the working directory's.
6. The test's git helper, repository config and `"."` redirect come from the project's
   `ai.miniforge.workflow.isolation-support`. Its own git calls drop every `GIT_*` variable.
   The CLI and executor calls under test inherit the test JVM's environment.
   `tasks/test_runner.clj` strips `GIT_*` from that environment at launch.

## Deployment Plan

Ships with the next CLI build. No config or data migration.

## Related Issues/PRs

1. Issue #2377 (gap 4 in its comment).
2. #766 introduced `:source-root` as distinct from the execution worktree.

Follow-ups, not in this PR:

1. DAG child tasks: `dag-sub-workflow/task-sub-opts` passes no `:repo-path`, so child task worktrees of a
   cross-repository spec still come from the working directory's repository.
2. `mf resume <id>`: `resume.clj` passes no `:repo-path` to `run-pipeline`, so a resumed cross-repository run
   cuts from the working directory's repository.
3. Governed runs: `sandbox-clone-target/infer-repo-url` falls back to the working directory's `origin` when
   the spec's repository has none, so a capsule could clone the wrong repository.

## Checklist

- [x] Regression test fails on main and passes here
- [x] Related CLI tests pass
- [x] clj-kondo and stratum-lint clean on changed files
- [x] Base branch: `main`; depends on nothing
