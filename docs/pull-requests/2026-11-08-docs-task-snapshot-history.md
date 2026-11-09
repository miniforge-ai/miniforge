<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: Design for a task snapshot history outside the agent's reach

## Overview

Adds `docs/design/task-snapshot-history.md`, a design with no code. It covers a per-task git snapshot store that
the platform owns, kept in a git dir outside the task worktree, with a baseline, phase and final snapshot for each
task, and restore by a verified SHA. It adapts PAODO-Workspaces' workspace git-versioning ADR to miniforge's linked
worktrees.

## Layer

Documentation. Base branch: `main`. Depends on nothing.

## Motivation

A 2026-11-08 review of PAODO-Workspaces against miniforge flagged that the agent can change the git history of a
local-mode task run, and that `host-git-guard` only reports shared-`.git` changes after the fact. The design
records four decisions:

1. The store sits beside linked worktrees, not a switch to clones.
2. Snapshots feed a new N3 event, a resume and rewind restore (N2-delta §8), and an N6 evidence field.
3. Governed capsules are left out for now.
4. Repack at release, with checkpoint-aligned retention.

## Changes in Detail

1. `docs/design/task-snapshot-history.md`: problem, reachability model, store layout, snapshot points, executor
   wiring, the four decisions, a six-PR implementation plan by stratum, and follow-ups found while tracing.

## Findings recorded in the doc

1. A `--from-phase` rewind restores the latest phase's work. Every phase overwrites the one bundle, and
   `restore-from-bundle!` fetches its branch tip; the kept phase's `:workspace/commit-sha` is ignored.
2. `host-git-guard` does not watch code-execution config keys. `git config core.hooksPath` run in a linked worktree
   writes the host's `.git/config`.

## Testing Plan

No code. Every claim about current behaviour was checked against `main` at `ea407dfc3`. The git mechanics the
design relies on were tested in a scratch repo with git 2.48.1:

1. `add --all --force` with `core.excludesFile=/dev/null` keeps ignored files, skips the worktree's `.git` file,
   and stores a nested repo as a gitlink.
2. `info/attributes` keeps the bytes on disk; without it, a work tree with `text=auto eol=crlf` was stored as LF.
3. `reset --hard` restores exactly, both in place and into a fresh worktree.
4. An inherited `GIT_INDEX_FILE` overrides an explicit `--git-dir`.

Snapshot cost was measured on this checkout.

## Deployment Plan

None. A docs-only change.

## Related Issues/PRs

- PAODO-Workspaces `doc/adr/accepted/platform/adr-workspace-git-versioning.md` (reviewed at `1b35459`).
- `components/dag-executor/src/ai/miniforge/dag_executor/host_git_guard.clj` (the detector this complements).

## Checklist

- [x] Design names the files each later PR touches
- [x] Claims checked against current code
- [x] markdownlint clean
- [ ] Copilot review addressed
