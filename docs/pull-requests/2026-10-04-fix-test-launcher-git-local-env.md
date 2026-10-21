<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: test launchers strip every repository-binding git variable

## Overview

A git hook hands its children `GIT_DIR` and other variables that bind git to the hook's repository. git obeys
them over `-C <dir>` and over the working directory. A test JVM that inherits them runs its fixture's
`git init` and `git config` against the developer's repository instead of its temp directory.

Before this PR the launcher-level strip was four names, written out in two places, and three bb test tasks
applied no strip. This PR:

1. Defines the launcher strip once, as the names `git rev-parse --local-env-vars` prints: 15 on git 2.40 and
   later, 16 on git 2.11 through 2.39.
2. Checks that list against the installed git in a test.
3. Routes every process `tasks/test_runner.clj` starts through it, `bb test:poly` included.

Base branch: `main`. Depends on: nothing. #2005 (open) makes the isolation fixture itself hermetic and touches
a different hunk of `tasks/test_runner.clj`.

## Motivation

### The list was short

`git-worktree-env-keys` held `GIT_INDEX_FILE`, `GIT_DIR`, `GIT_WORK_TREE` and `GIT_COMMON_DIR`.
`scripts/test-changed-bricks.bb` repeated the same four. git 2.48.1 lists 15:

```text
GIT_ALTERNATE_OBJECT_DIRECTORIES GIT_CONFIG GIT_CONFIG_PARAMETERS GIT_CONFIG_COUNT GIT_OBJECT_DIRECTORY
GIT_DIR GIT_WORK_TREE GIT_IMPLICIT_WORK_TREE GIT_GRAFT_FILE GIT_INDEX_FILE GIT_NO_REPLACE_OBJECTS
GIT_REPLACE_REF_BASE GIT_PREFIX GIT_SHALLOW_FILE GIT_COMMON_DIR
```

`GIT_CONFIG` is one of the 11 that were missing. With only the four dropped, an inherited
`GIT_CONFIG=<other repo>/.git/config` sends a plain `git config user.name X` into that other repository.
The new `test-runner-test` reproduces this against a decoy: with the four-name list swapped back in, the
decoy's config file changes.

git 2.11 through 2.39 list a sixteenth name, `GIT_INTERNAL_SUPER_PREFIX` (`environment.c` at v2.39.0; gone
in v2.40.0). Debian 12 (git 2.39) and Ubuntu 22.04 (git 2.34) ship such a git. The list carries the name so
the strip is complete there too.

### Three launchers had no strip

`bb test:poly`, `test-runner/integration` and `test-runner/conformance` started their test JVMs with the
launch environment as it was. `bb test:all` runs the first two. `test-runner/graalvm` was the same, and
`bb pre-commit` runs it inside the hook.

### Why a list and not the `GIT_` prefix

The fixture in #2005 drops the whole prefix. That is right for a fixture: its git commands need nothing from
the environment. A launcher is different. It starts `clojure`, which fetches git dependencies with the git on
`PATH`. It also starts a JVM that a developer or CI may have configured on purpose. Dropping the prefix would
take:

1. `GIT_CONFIG_GLOBAL`, `GIT_CONFIG_SYSTEM`, `GIT_CONFIG_NOSYSTEM`: the supported way to give a test run its
   own config scopes.
2. `GIT_AUTHOR_*` and `GIT_COMMITTER_*`: the identity on a runner with no global config.
3. `GIT_SSH_COMMAND`, `GIT_ASKPASS`, `GIT_TERMINAL_PROMPT`: what a dependency fetch needs to authenticate, or
   to fail instead of hanging.

None of these binds a git command to a repository. `git rev-parse --local-env-vars` is git's own answer to
"which variables do": its manual describes the output as the variables "local to the repository". The list is
written out in code rather than read from git at launch. The launcher needs no extra process that way, and a
test can compare the two.

## Changes in Detail

1. `components/bb-test-runner/.../stable_derived.clj`: `git-local-env-vars` replaces `git-worktree-env-keys`
   and holds the 16 names. `sanitize-git-worktree-env` drops them. Its name and signature are unchanged.
2. `tasks/test_runner.clj`:
   1. `run-stream!` passes its environment through `sanitize-git-worktree-env` on every call. It uses `:env`
      from a leading opts map when that holds one and the process's own environment otherwise. A caller
      cannot leave the step out by accident. `integration`, `conformance`, `graalvm` and `precommit-smoke`
      all start their process through it.
   2. `poly-all` is the body of `bb test:poly`, moved out of `bb.edn` so it goes through `run-stream!`.
   3. `precommit-smoke` loses its own copy of the strip.
   4. `graalvm` also resolves its classpath with `clojure -Spath` before the test starts. That call now gets
      the stripped environment as well.
3. `bb.edn`: `test:poly` is now `(test-runner/poly-all)`.
4. `scripts/test-changed-bricks.bb`: `test-env`, the test JVM's environment, is built with
   `sanitize-git-worktree-env` instead of its own four names. The Polylith change query keeps the environment
   it had: it asks about the launch repository and must stay bound to it.
5. `resources/precommit-smoke-tests.edn`: adds `test-runner-test`.

### Behaviour change

`GIT_CONFIG_PARAMETERS` and `GIT_CONFIG_COUNT` are on git's list, so config passed as `git -c key=value
commit` or through `GIT_CONFIG_COUNT` no longer reaches a test process or the launcher's own `git tag` and
Polylith queries. A test in a temp repository no longer sees the committer's `-c user.name`, which outranks
the value the test sets with `git config`. Nothing in this repository or its CI sets either variable for a
test run. To give a test run its own config, set `GIT_CONFIG_GLOBAL`.

## Testing Plan

1. `stable-derived-test`, three new tests:
   1. `test-sanitize-git-worktree-env-drops-every-variable-the-installed-git-lists` runs
      `git rev-parse --local-env-vars`, sets every name it prints, and expects an empty map back. A git that
      adds a variable fails this test until the list has it. A git that lists fewer names passes.
   2. `test-sanitize-git-worktree-env-drops-the-variable-only-older-git-lists` covers
      `GIT_INTERNAL_SUPER_PREFIX`, which a current git does not print.
   3. `test-sanitize-git-worktree-env-keeps-variables-set-on-purpose` passes eight `GIT_*` variables that bind
      to no repository and expects all eight back.
2. `test-runner-test` (new, `development/test`), two tests:
   1. `test-run-stream-keeps-git-off-the-repository-a-hook-names` builds a decoy repository and a target
      directory. It aims `GIT_DIR`, `GIT_INDEX_FILE`, `GIT_PREFIX` and `GIT_CONFIG` at the decoy and starts
      `git config` in the target through `run-stream!`. The write lands in the target and the decoy's config
      is byte-identical. The same write with the environment unstripped reaches the decoy, which shows the
      environment is one that redirects. The test lays its `GIT_*` variables over `PATH` and `HOME` alone, so
      it can reach the decoy and nothing else.
   2. `test-every-launcher-starts-its-processes-with-the-sanitized-environment` calls `poly-all`,
      `integration`, `conformance`, `graalvm` and `precommit-smoke` with no environment passed. The process
      entry points only record. Every recorded environment must be the sanitizer's output.
3. The six tests, run against weakened code swapped in with `with-redefs`. Columns: the installed-git check,
   the older-git name, the keep list, the decoy test, the launcher wiring test.

   | Variant | installed git | older git | keep list | decoy | wiring |
   |---|---|---|---|---|---|
   | As written | green | green | green | green | green |
   | Four-name list (`main`) | red | red | green | red | green |
   | 15 names, older-git name left out | green | red | green | green | green |
   | `GIT_` prefix | green | green | red | green | green |
   | No strip | red | red | green | red | green |
   | `run-stream!` as on `main` | n/a | n/a | n/a | red | red, all five launchers |

4. From a real pre-commit hook. A scratch copy of `main` was committed into a decoy launch repository with a
   linked worktree. `git commit` in that worktree ran the namespaces under audit. The decoy's git directory
   was hashed before and after each one.
   1. With the hook's environment inherited, all fifteen wrote to the decoy. The audit table below has the
      detail.
   2. Started through this branch's `run-stream!`, with `GIT_CONFIG` exported on top of what the hook sets:
      15 namespaces, 163 tests, 566 assertions, no failures. The decoy was unchanged after every namespace
      and its hook was never re-entered.
5. `bb test:poly` argv checked with `run-stream!` stubbed: `clojure -M:poly test :all`, unchanged.
6. `bb check:bb-classpaths`: passing. `clj-kondo` and `stratum-lint` on the changed files: no findings.
7. `scripts/test-changed-bricks.bb` was run from `main` and from this branch: 649 namespaces, 6,588 tests
   each. The shell set none of the 16 variables, so both versions gave their subprocesses the same
   environment. The script's parallel mode fails differently on every run:
   1. `main`'s script: 26 failures, 4 errors.
   2. This branch's script, two runs: 48 failures and 2 errors, then 66 and 3.

   The failing files overlap and none is in a brick this PR changes. No bb task or CI job calls the script.

`test-runner-test` runs in the pre-commit smoke set, like `commit-budget-test` and `lint-test`. CI runs
`bb test` and `bb test:all`; neither loads `development/test`. A change that breaks the wiring in
`tasks/test_runner.clj` is caught by the hook and not by CI.

## Deployment Plan

Development tooling only; nothing ships. No action is needed in an existing checkout.

## Audit: test namespaces that depend on the launcher

Fourteen test files run `git init` and `git config user.*` in a temp directory through `-C <dir>` or `:dir`,
and pass no `:env`. They are thirteen test namespaces and `host_git_fixtures.clj`, which two test namespaces
use. Each file was read. The fifteen namespaces were then run from the scratch hook described above, with the
hook's environment inherited. Every one wrote to the launch repository. All of them set `core.bare = true`, a test
`user.name` and `user.email` in its shared config; all but one also set `commit.gpgsign`.

| Namespace | Tests under the leak | Also written to the launch repository |
|---|---|---|
| `agent` `curator-test` | 16 run, 2 errors | nothing further |
| `dag-executor` `host-git-fixtures`, through `host-git-guard-test` | 12 run, 3 failures | `[remote "origin"]`, `url.insteadOf`; committing branch moved; `feature`, `origin/main`; worktree `HEAD`; index |
| `dag-executor` `host-git-fixtures`, through `host-guarded-test` | 5 run, 2 failures | `[remote "origin"]`; committing branch moved; two `task-*` branches, `origin/main`; index |
| `gate` `stale-references-test` | 7 run, 1 error | committing branch moved; index |
| `phase-software-factory` `release-test` | 29 run, 5 failures, 1 error | `tag.gpgsign`; four `mf/*` branches; worktree `HEAD`; index |
| `pr-lifecycle` `conflict-resolution-test` | 13 run, 7 errors | `tag.gpgsign`; committing branch moved; index |
| `release-executor` `git-test` | 16 run, **all green** | committing branch moved; `release/x` branch; worktree `HEAD`; index |
| `workflow` `merge-parent-branches-integration-test` | fixture threw, run aborted | `tag.gpgsign` |
| `workflow` `merge-resolution-test` | fixture threw, run aborted | nothing further |
| `workflow` `runner-environment-test` | 4 run, 1 failure | committing branch moved |
| `development` `bench-test` | 7 run, 12 failed assertions | `[remote "origin"]`; committing branch moved; worktree `HEAD`; index |
| `projects/miniforge` `phase.handoff-test` | 5 run, 2 errors | nothing further |
| `projects/miniforge` `release-executor.artifact-validation-integration-test` | fixture threw, run aborted | `tag.gpgsign` |
| `projects/miniforge` `workflow.artifact-persistence-test` | fixture threw, run aborted | nothing further |
| `projects/miniforge` `workflow.release-integration-test` | 17 run, **all green** | index |

Two findings beyond the config writes:

1. `release-executor` `git-test` and `workflow.release-integration-test` pass with no failure while writing
   to the launch repository. A green run is not evidence of containment.
2. Thirteen of the fifteen made commits that re-entered the launch repository's own pre-commit hook, up to
   24 times in one namespace. In a real checkout each re-entry runs that checkout's hook again.

## Not in this PR

1. The fourteen files above still have no scrub of their own. Proposed home for one shared helper: a
   new component, `components/hermetic-git`, with its code in `src` behind an interface. Brick tests may only
   depend on another brick through its interface. Project-level tests cannot load a brick's `test` directory,
   which is why the isolation fixture exists as two identical files today. The component would hold:
   1. The list of names and the launcher strip, moved from `bb-test-runner`, which would delegate.
   2. A fixture-grade environment: the whole `GIT_` prefix dropped, both config scopes aimed at an empty
      file, identity supplied through `GIT_AUTHOR_*` and `GIT_COMMITTER_*`. `commit-budget-test` already
      builds this privately. With it a fixture needs no `git config` write at all.
   3. `git!` (run in a directory under that environment, throw on a non-zero exit) and `init-repo!`.

   Callers to move: the fourteen files, both isolation fixture twins from #2005, `commit-budget-test`,
   `test-runner-test`, and the three-name strip in `repo-index`'s `scanner.clj`. Three or four PRs under the
   600-line budget: the component, brick tests, project and `development` tests, existing private scrubs.
2. A way to keep it that way: a launcher mode that aims `GIT_DIR` and `GIT_CONFIG` at a throwaway decoy
   instead of stripping them. The run fails if the decoy changed. The audit driver used for the table does
   this by hand. Run in CI it would catch a new unscrubbed fixture in the PR that adds it.
3. `bb ccov` starts Cloverage, which runs tests, through `bb-dev-tools` with the launch environment
   unstripped. `bb-test-runner/run-coverage` does the same.
4. No CI job runs the tests under `development/test`, this PR's `test-runner-test` among them.
5. `run-stream!` strips `:env`. A caller that adds a listed variable back through `:extra-env` gets it; no
   caller does.
6. `GIT_CONFIG_KEY_<n>` and `GIT_CONFIG_VALUE_<n>` stay in the environment. git ignores them once
   `GIT_CONFIG_COUNT` is gone.

## Related Issues/PRs

1. #2005: the isolation fixture's own containment, and the incident record.
2. #1894: the fixture, the first scrub, and the smoke runner's strip.
3. `docs/pull-requests/2026-09-04-test-hygiene-codex-env-and-host-isolation.md`, section "Hook environment".

## Checklist

- [x] Launcher strip defined once, from git's own list
- [x] List checked against the installed git
- [x] Every `tasks/test_runner.clj` launcher and `bb test:poly` go through the strip, pinned by a test
- [x] Each new test shown to fail when its defence is removed
- [x] Fourteen dependent test files read, and run under an inherited hook environment in a scratch repository
- [ ] Shared hermetic-git helper (follow-up)
