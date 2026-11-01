<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: Write down the PR merge gate and load it in every session

## Overview

`agents.md` now states the PR merge gate the operator set: PRs open ready for
review, and a PR merges once four gates pass on its current head. The gates
are adversarial review (dewey 720), rules review against the applicable
standards, green CI, and a Copilot review of the current head. The owning
session then squash-merges it without waiting for the operator, unless gate 1
or 2 cannot be completed.

A new `.claude/CLAUDE.md` imports `agents.md`, so every Claude Code session
loads the gate, including Linux cloud sessions that never loaded the
lowercase `claude.md`.

## Motivation

The operator has given this instruction more than once, and sessions kept
missing it. On 2026-11-01, #2296 and #2305 merged on green CI and a clean
Copilot review alone, skipping gates 1 and 2.

Part of the reason: the rule lived only in conversation, and the repo's
instructions never reached cloud sessions. Claude Code looks for `CLAUDE.md`
(or `.claude/CLAUDE.md`); the repo has a lowercase `claude.md`, which a
case-insensitive macOS filesystem resolves but a Linux one does not. Nothing
loads `agents.md` into a Claude Code session on its own.

## Changes in Detail

1. `agents.md`: a "PR Merge Gate" section. It covers: no drafts; the four gates
   on the current head; gates 1 and 2 before the PR opens and before every
   later push; re-requesting Copilot after a push, and stopping if Copilot
   posts no review; the owning session squash-merges; stopping to ask the
   operator when gate 1 or 2 cannot be completed (dewey 720); subagent reviews
   as an extra pass only; scope for trivial and documentation-only PRs.
2. `.claude/CLAUDE.md`: new; imports `agents.md` with `@../agents.md`. It sits
   in a different directory from `claude.md`, so the two do not collide on a
   case-insensitive filesystem, and `agents.md` stays the single source.
3. `claude.md`: unchanged from main (an earlier commit on this branch added a
   copy of the gate; it is removed so the two cannot drift).

## Testing Plan

Documentation only; no code or test changes.

- markdownlint (the repo's `.markdownlint.jsonc`, pinned CLI version) on the
  changed files. CI's markdownlint step does not fail a PR, so it was run
  separately.
- After merge, a new cloud session in this repo should list `agents.md` among
  its loaded instructions. The `@` import is Claude Code's documented memory
  import syntax; nothing in this repo can test it.
- plainspeak (the pre-commit prose lint) was not run: its repository is not
  reachable from the session that wrote this.

## Deployment Plan

None. Claude Code reads `.claude/CLAUDE.md` when a session starts in this repo.

## Related Issues/PRs

- #2296, #2305: merged without gates 1 and 2.
- #2317: follow-up fixes from the post-merge reviews of #2296. The #2305
  follow-up is opened separately.

## Not in this PR

- The same rule in the shared standards (`miniforge-standards`), so sessions
  in other repos load it too. That needs push access to that repository.
- Renaming `claude.md` to `CLAUDE.md`: the knowledge loader (`loader.clj`) and
  a context-pack test name `claude.md`, so that is a separate change.

## Checklist

- [x] Gates 1 and 2 run on this PR (adversarial pass on every factual claim;
      rules review against 721, 600, 810 and markdownlint)
- [x] No code changes
- [x] No user-facing strings added
