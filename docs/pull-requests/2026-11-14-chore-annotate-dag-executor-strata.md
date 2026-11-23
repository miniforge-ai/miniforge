<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# chore: stratum annotation of three dag-executor files

## Overview

`bb lint:stratum --fix` output for three files, committed on its own. The
fix adds `^{:stratum n}` metadata to every def, regenerates the `Layer N`
headings from the same-file reference graph, and regroups defs under them.
No form changed.

## Motivation

The split of `protocols/impl/worktree.clj` edits these three files to point
their references at the new namespaces. Each has `Layer N` headings but no
stratum metadata, so the first staged edit makes the pre-commit hook rewrite
the whole file. Landing that rewrite here keeps the split's diff to the
lines the split changes.

1. `interface.clj` had headings `Layer 0` through `Layer 12` (plus `0.5`
   and `6.5`) that did not match its reference graph. Most of its defs are
   pass-throughs to other namespaces, so they move to Layer 0. Only
   `create-dag-from-tasks` (Layer 1) and `execute-dag` (Layer 2) refer to
   other defs in the file.
2. `protocols/worktree_copy_test.clj` and
   `protocols/impl/worktree_lifecycle_test.clj` gain metadata and a `Layer
   1` group for the tests that use a same-file helper.

## Changes in Detail

1. The autofix output, as the hook would produce it.
2. By hand, comments only: in `interface.clj` the two non-integer headings
   (`Layer 0.5`, `Layer 6.5`) are removed, since the linter does not read
   them and they no longer sat at a boundary. The `;; Convenience
   functions` comment moves to `create-dag-from-tasks`, the first of the two
   convenience functions; on `main` it headed an empty section. In the
   lifecycle test the `;;----- <section>` banners become `;; <section>`
   comments, and the section label is added above
   `release-environment-without-matching-registry-entry-is-safe-test`,
   which the regroup moved away from its section.

## Testing Plan

1. Behavior-neutral check, for each file: every top-level form of the
   `main` version and the new version was read with `*read-eval*` false.
   The multisets of `pr-str` forms are equal (102, 8, and 23 forms), and the
   only metadata difference on any def name is the added `:stratum`.
2. `stratum-lint` (pinned sha from `tasks/stratum.clj`) reports no
   findings on the three files, and a second `--fix` changes nothing.
3. clj-kondo: the one warning left (a redundant `let` in the lifecycle
   test) is also on `main`.
4. dag-executor test suite passes.

## Deployment Plan

None.

## Related Issues/PRs

Base branch: `main`. Depends on: nothing.

1. Step 1 of 3. Next: split `protocols/impl/worktree.clj` (7 strata) into
   namespaces of at most 3. Then: `archive-bundle!` runs git with hooks
   and fsmonitor off (follow-up 3 of #2463), which the split unblocks.

## Checklist

- [x] Autofix output only, plus comment fixes
- [x] Behavior-neutral check
- [x] Tests pass
