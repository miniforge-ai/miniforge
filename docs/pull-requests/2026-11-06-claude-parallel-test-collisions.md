<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(test): let test suites run side by side and keep them out of ~/.miniforge

## Overview

Test-only fixes. Two copies of the test suite can now run at the same time on
one machine. A full suite also no longer leaves `task-*` branches in the
repository, run directories in `~/.miniforge/checkpoints`, or a failed
`:claude` backend in `~/.miniforge/backend_health.edn`.

## Motivation

Local JVM test runs are serialized through a machine-wide lock, so every
Claude and Codex session waits for one suite at a time. Before allowing two or
three at once, we ran three copies of CI's test sequence (`bb test:poly`,
`bb test:integration`, `bb test:dev`) at the same time on main (6cafc08c8):

1. Two of the three `test:poly` runs failed in
   `execute-command-kills-process-tree-at-deadline-test`. The test checks that
   a timed-out process tree was killed by running `pgrep -f "sleep 3607"`.
   That search covers the whole machine, so it found the sleeper of the same
   test running in another suite.
2. One full sequence created 12 `task-*` branches in the shared repository and
   12 run directories in `~/.miniforge/checkpoints`. They came from two
   namespaces that call `runner/run-pipeline` without the
   `isolation/with-isolated-host` fixture that other pipeline tests register.
3. It also rewrote `~/.miniforge/backend_health.edn`: three failed `:claude`
   calls, a cooldown on `:claude`, and `:default-backend :openai`. Real runs
   read that file when choosing a backend. The writer is
   `dag-resilience-failover-test`, whose failover test ran the real
   self-healing calls. Its assertion accepted `:continue` or `:pause` because
   the outcome depended on the state already in that file.

Everything else passed in all three runs, and no other test wrote under
`~/.miniforge`.

## Changes in Detail

1. `worktree_execute_test.clj`: the sentinel command is now
   `sleep 3600.<JVM pid>`, so the reap check matches only this JVM's sleeper.
   `sleep` takes decimal seconds on BSD and GNU. A marker in a shell comment
   would not work: `sh -c` execs `sleep` directly and the comment is not in
   the process's arguments (checked on macOS).
2. `phase_metrics_single_count_test.clj` and
   `cost_unavailable_metrics_test.clj` register
   `(use-fixtures :once isolation/with-isolated-host)`, which points
   `run-pipeline`'s repository, worktrees, archives and checkpoint root at a
   temp tree.
3. `dag_resilience_failover_test.clj`: the failover test stubs
   `self-healing/record-backend-call!` and
   `self-healing/trigger-backend-switch!` with recorders that return the real
   functions' shapes. It now asserts the exact decision
   (`{:action :continue :new-backend :openai}`) and the calls
   (`[:record :claude false]`, then `[:switch :claude :openai]`). The file had
   layer headings but no `^{:stratum}` metadata, so the pre-commit autofix
   tagged its forms and regrouped its headings. That rewrite only adds
   metadata and moves comments.

## Testing Plan

1. With a stray `sleep 3607` and another JVM's `sleep 3600.12345` running,
   the main version of `worktree-execute-test` fails and the new one passes.
2. On main, the two pipeline namespaces leaked 3 and 1 `task-*` branches and
   as many checkpoint directories; with the fixture they leak none. Both pass.
3. Run under a temp `MINIFORGE_HOME`, the main version of
   `dag-resilience-failover-test` writes `backend_health.edn` there and the new
   one writes nothing. With `find-healthy-backend` changed to record success,
   the new test fails; the old assertion accepts either outcome.
4. Three copies of the CI test sequence run at the same time with these fixes;
   the result is recorded below.

## Deployment Plan

None. Test code only.

## Related Issues/PRs

1. #2013 strips git's repository-binding variables from every test launcher,
   which matters for suites started from a git hook. It is separate from these
   collisions.
2. #2237 kept the agent and self-healing tests out of the backend health file;
   the workflow tests were not part of that audit.

## Checklist

- [x] Each fix fails on main and passes with the change
- [x] clj-kondo clean on the changed files
- [ ] Three side-by-side CI test sequences pass with the fixes
