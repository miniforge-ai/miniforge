<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: split the worktree executor into namespaces of at most three strata

## Overview

`protocols/impl/worktree.clj` (1087 lines, 7 strata) is split into nine
namespaces, each with at most three. The code is moved, not rewritten. Two
functions with no callers are deleted, and fourteen private defs become
public because their callers now sit in another namespace.

## Motivation

The pre-commit `bb lint:stratum` fails any commit that touches a file over
the 3-stratum budget (rule 210, SL003). `worktree.clj` was at 7, so no fix
to the worktree executor could be committed, however small. The change
waiting on this is follow-up 3 of #2463: `archive-bundle!` running its git
calls with hooks and fsmonitor off.

A split cannot be sliced into smaller commits. Each partial extraction
leaves `worktree.clj` over budget and fails the hook again. It is one
commit.

## Changes in Detail

1. Namespaces, under `protocols/impl/`:

   | Namespace | Holds | Strata |
   |---|---|---|
   | `worktree` | `run-git`, `run-shell`, and the git queries on them: `git-version`, `resolve-branch-sha`, `remove-worktree`, `derive-parent-repo-path`, `notify-file-written!` | 3 |
   | `worktree.executor` | `WorktreeExecutor`, `create-worktree-executor`, `execute-command`, `default-max-concurrent` | 3 |
   | `worktree.provision` | `create-worktree` and the JVM lock around `git worktree add` | 2 |
   | `worktree.archive` | `archive-bundle!`, `restore-from-bundle!`, the commit-message and base-ref defaults | 2 |
   | `worktree.archive-steps` | the single git steps `archive-bundle!` composes | 3 |
   | `worktree.paths` | base-path and archive-dir resolution, the legacy `/tmp` advisory, `ensure-directory` | 3 |
   | `worktree.registry` | the worktree lifecycle registry | 2 |
   | `worktree.files` | `copy-file-to` and `copy-file-from` with the path-escape check | 3 |
   | `worktree.drain` | stdout/stderr drain settling for a deadline-bounded `execute-command` | 3 |

   The git plumbing keeps the `worktree` namespace name, so its callers do
   not change: `scratch_gc_queue.clj` (at 4 strata on `main`, so any edit
   to it would fail the hook too) and every test that redefines
   `worktree/run-git`.
2. `execute-command` lives with the executor, not with the drain helpers.
   Together with them it is a 4-stratum chain.
3. Deleted, no callers anywhere in the repository: `create-worktree!` and
   `remove-worktree!`.
4. Made public because a caller is now in another namespace:
   `resolve-branch-sha`, `settle-drains!`, `kill-and-collect!`,
   `timed-out-result`, `find-workflow-id-by-worktree`,
   `default-commit-message`, `default-base-ref`, `current-branch`,
   `ensure-named-branch!`, `already-clean?`, `commits-ahead`,
   `stage-all!`, `maybe-commit`, and `bundle-result`.
5. Callers point at the new namespaces, with no re-export shims:
   `executor.clj`, `interface.clj`, the REPL comment in `host_guarded.clj`,
   and seven test files. `executor.clj`'s namespace docstring names the new
   file.
6. Comments: the `;; ===` section banners are dropped, since each section
   is now a namespace. The note on `legacy-tmp-warned?` names the alias the
   tests use. The archive steps' note on returning a result for every exit,
   never a silent zero, moves into the `worktree.archive-steps` namespace
   docstring, without its PR history.

## Testing Plan

1. Behavior-neutral check. Every top-level form of `main`'s `worktree.clj`
   was compared with every form of the nine new files, all read with
   `*read-eval*` false. References to the old namespace's own defs, and to
   any of the new aliases, were normalized to one marker, and `defn-` was
   read as `defn`. The two multisets differ by exactly the two deleted
   forms. The visibility comparison finds exactly the fourteen defs above
   made public and none made private.
2. The same comparison on each caller: forms are equal once worktree
   aliases are normalized, for `interface.clj`, `executor.clj`, and the
   seven test files. `host_guarded.clj` differs only in its REPL `(comment
   …)` block.
3. stratum-lint: every changed file passes, and the nine namespaces sit at
   2 or 3 strata.
4. clj-kondo: no new warnings. The three left are also on `main`.
5. dag-executor test suite passes.

## Deployment Plan

None. Nothing outside dag-executor names these namespaces, and
`interface.clj` keeps its public vars.

## Known gaps

1. Moved code keeps its raw English error strings (`"git add -A failed"`
   and similar), which rule 050 would route through the message catalog.
   That predates this change and is left for its own PR, to keep this one a
   move.
2. The new namespaces have no `(comment …)` REPL block. The original file
   had none either.

## Related Issues/PRs

Base branch: `chore/annotate-dag-executor-strata` (#2525), which lands the
stratum annotation of three files this PR edits. Retarget to `main` once it
merges.

1. Step 2 of 3. Next: `archive-bundle!` runs git with hooks and fsmonitor
   off (follow-up 3 of #2463).

## Checklist

- [x] Nine namespaces, each at most 3 strata
- [x] Callers updated, no shims
- [x] Dead code removed
- [x] Behavior-neutral check
- [x] Tests pass
