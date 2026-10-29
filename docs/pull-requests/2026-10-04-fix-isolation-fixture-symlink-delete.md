<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: the isolation fixture's cleanup cannot delete through a symbolic link

## Overview

`delete-tree!` in the isolation fixture entered symbolic links to directories and deleted the files
in their targets, outside the temp root it was handed. The fixture's own `git init` could put such a
link under the root. This PR makes `delete-tree!` delete a link as a link, stops `git init` from
copying a template, and adds a regression test for each.

This branch starts from the head of #2005 (`fix/isolation-fixture-host-config-leak`), which rewrites
the same functions and was still open. The PR targets that branch. Once #2005 merges, retarget this
PR to `main`.

## Motivation

### The defect

1. `delete-tree!` recursed on `File.isDirectory` and `File.listFiles`. `isDirectory` follows symbolic
   links. A link to a directory anywhere under the root was entered and its target emptied.
2. `init-host-repo!` ran `git init` with the developer's global config. `git!` drops
   `GIT_TEMPLATE_DIR` with the rest of the `GIT_*` prefix. `init.templateDir` in the global config
   still applied, and git copied that directory into the host repository.
3. When the template's `hooks` entry is a symbolic link, git recreates the link in the new
   repository. `with-isolated-host` deletes the temp root when it unwinds, so the files in the
   link's target went with it.

The trigger is an `init.templateDir` whose `hooks` entry links to a shared hooks directory. The
reporting machine does not set one, so nothing was lost there. A developer with that setup would
lose the hook files on every run of a namespace that registers the fixture.

`runner_extended_test.clj` also calls `delete-tree!` directly, on four plain temp roots.

### Reproduction

A scratch script ran these against the fixture as it stands on #2005's head, with git 2.48.1 and a
scratch `HOME`:

1. A root holding a link to an outside directory: the outside file was deleted.
2. A root that is itself a link to an outside directory: the outside file was deleted.
3. A scratch `HOME` whose config names a template directory with a linked `hooks` entry: the host
   repository's `.git/hooks` was a link. The hook file in its target was gone after `delete-tree!`.
4. A root holding a link whose target is missing: the link stayed and the root was not deleted.
   `File.exists` follows links too, so the old guard skipped it.

After the change the same script leaves every target intact and removes every root. The seed commit
still lands with `--template=`.

### The checkpoint supports need no change

`checkpoint_test_support.clj` and its project twin `checkpoint_root_support.clj` delete with
`babashka.fs/delete-tree` 0.5.34. Its source walks with `Files/walkFileTree` and no `FOLLOW_LINKS`
option. The script confirmed it: a root holding a directory link and a file link was deleted and
both targets stayed.

## Changes in Detail

Both fixture twins change identically: `components/workflow/test/.../isolation_test_support.clj`
and `projects/miniforge/test/.../isolation_support.clj`.

1. `delete-tree!` asks `Files/isSymbolicLink` before the directory check. A link is deleted as a
   link and never entered. The `File.exists` guard is gone, so a link with a missing target is
   deleted as well.
2. `init-host-repo!` passes `--template=` to `git init`. No template is copied.

`git!` is untouched and references no same-file var. The namespace stays at three strata.

Two behaviours change as a result:

1. The host repository has no `.git/hooks`, `.git/info` or `.git/description`. No source file in
   `components/` or `bases/` names those paths, and the suites below pass without them.
2. `delete-tree!` returns `false` for a missing path where it returned `nil`. No caller reads it.

New tests, in both twin test namespaces (`isolation_test_support_test.clj`,
`isolation_support_test.clj`):

1. `test-delete-tree-removes-a-link-and-keeps-its-target` builds a root holding a link to an outside
   directory and a link to a missing file. After `delete-tree!` on the root, the outside file exists
   and the root does not.
2. `test-host-repo-copies-no-template` points `HOME` at a temp directory whose git config names a
   template directory holding a marker file. `init-host-repo!` leaves no marker in the host
   repository. A plain `git init` in the same environment does copy it, which shows the environment
   is one where a template applies.

## Testing Plan

1. New tests, both twins: 5 tests, 10 assertions each, passing.
2. The brick tests run against four weakened copies of the fixture:
   1. Link check removed: 1 assertion red.
   2. `File.exists` guard put back around the new body: 1 red.
   3. `--template=` removed: 1 red.
   4. The fixture as on #2005's head: 3 red, across both new tests.
3. The project twin's tests against its fixture as on #2005's head: the same 3 red.
4. The nine workflow brick namespaces that use the fixture, `runner-extended-test` among them, run
   directly: 118 tests, 292 assertions, 0 failures.
5. The four project namespaces that use the twin, run through the project `deps.edn` classpath:
   64 tests, 189 assertions, 0 failures.
6. Each fixture commit passed the pre-commit hook: smoke set of 18 namespaces, 361 tests.
7. The twins compared with `diff` from the first layer banner down, fixture and test: identical.

Not run locally: the full `bb test:integration` list, and `meta_agent_test` and
`meta_agent_e2e_test`, which register the twin from outside the project's `test` directory.

## Deployment Plan

Test code only; nothing ships. No checkout needs repair.

## Related Issues/PRs

1. #2005 — the base of this branch. Item 3 of its "Not in this PR" list is this defect.
2. #1894 — the PR that added the fixture.

## Not in this PR

1. Other test deleters in this repository follow links the same way and are unchanged:
   1. `components/dag-executor/test/.../host_git_fixtures.clj`: the same `isDirectory` recursion,
      on repositories it creates with an unscrubbed `git init`.
   2. `development/test/bench_test.clj`: the same recursion, on repositories it initialises.
   3. `file-seq` deleters in `components/agent/test/.../file_artifacts_test.clj`,
      `file_artifacts_extended_test.clj`, `development/test/commit_budget_test.clj` and
      `bases/cli/test/.../worktree_test.clj`. `file-seq` enters a linked directory.
2. `--template=` covers the fixture's own `git init` only. A `git init` or `git clone` made by code
   under test would still copy a template into the root. The link-safe `delete-tree!` is what keeps
   that from reaching outside it.

## Checklist

- [x] Defect reproduced against the fixture as on #2005's head
- [x] Both fixture twins changed identically, bodies compared with `diff`
- [x] Regression tests added for both twins and shown to fail without each defence
- [x] Checkpoint supports confirmed link-safe by source and by run
- [x] Fixture namespace still at three strata; each commit under the 200-line budget
