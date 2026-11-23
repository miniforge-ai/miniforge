<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor(pr-lifecycle): remove ungoverned merge entry points

PR #2276.

## Overview

Removes the pr-lifecycle entry points that ran `gh pr merge` without a grant: `enable-auto-merge!` and
`disable-auto-merge!` are deleted, and the interface no longer exports `merge-pr!`.

## Motivation

`enable-auto-merge!` and `disable-auto-merge!` merged or changed auto-merge with no grant, no effect record and no head
binding, and nothing called them. The exported `merge-pr!` offered the same ungoverned path to any caller. The governed
path (`attempt-merge`) is the only one that issues and checks authority.

## Changes in Detail

1. Delete `merge/enable-auto-merge!` and `merge/disable-auto-merge!` and their interface vars.
2. Drop the `merge-pr!` interface var. `merge.clj` keeps `merge-pr!`; the governed orchestration calls it inside the component.

## Testing Plan

No callers in the workspace (grep across components, bases, projects, development). Ran pr-lifecycle merge, integration
and controller tests: 84 tests, 0 failures. Pre-commit poly check passes.

## Deployment Plan

None. A caller outside this workspace using these vars would fail to compile; that caller was merging without authority.

## Related Issues/PRs

Part of the effect-authority work (#2258, #2272).

## Checklist

- [x] Commits under 200 reportable lines
- [x] PR doc: `docs/pull-requests/2026-10-30-fix-remove-ungoverned-auto-merge.md` (rule 721), added after merge
