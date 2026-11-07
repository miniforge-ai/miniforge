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
`~/.miniforge`. Two of the `test:poly` runs stopped at the first failure, so
a second side-by-side run with fixes 1 to 3 covered the whole suite. All three
`test:poly` runs passed. One `test:integration` run failed
`backend-health-test/test-cooldown-expires`. That namespace keeps its health
file at `<java.io.tmpdir>/test_backend_health.edn`, a name every JVM on the
machine shares, and its fixture deletes the file around each test. The test
also read back a 1 ms cooldown after a file write and read. A scan for other
fixed names under `java.io.tmpdir` found none that a test writes.

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
4. `projects/miniforge/test/.../backend_health_test.clj`: the health file
   name carries the JVM's PID. `test-cooldown-expires` writes a switch time
   an hour back, in the Instant-string form `trigger-backend-switch!` stores.
   It checks that a 30-minute cooldown has expired and a 2-hour one has not,
   and no longer sleeps.

## Testing Plan

1. With a stray `sleep 3607` and another JVM's `sleep 3600.12345` running,
   the main version of `worktree-execute-test` fails and the new one passes.
2. On main, the two pipeline namespaces leaked 3 and 1 `task-*` branches and
   as many checkpoint directories; with the fixture they leak none. Both pass.
3. Run under a temp `MINIFORGE_HOME`, the main version of
   `dag-resilience-failover-test` writes `backend_health.edn` there and the new
   one writes nothing. With `find-healthy-backend` changed to record success,
   the new test fails; the old assertion accepts either outcome.
4. With another process deleting the shared health file, the main version of
   `backend-health-test` fails two tests and the new one passes. With
   `in-cooldown?` changed to ignore the period, the new
   `test-cooldown-expires` fails.
5. Three copies of the CI test sequence ran at the same time with fixes 1
   to 3. All three `test:poly` runs passed, taking about 18 minutes each
   against about 9.5 on CI. All three `test:dev` runs and two of three
   `test:integration` runs passed. No
   `task-*` branch, no miniforge checkpoint and no backend health write
   reached the repository or `~/.miniforge`.

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
