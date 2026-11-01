<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: Write down the PR merge gate for every session

## Overview

`claude.md` and `agents.md` now state the PR merge gate the operator set: PRs
open ready for review, and a PR merges once four gates pass, in order. The
gates are adversarial review (dewey 720), rules review against the applicable
standards, green CI, and a resolved Copilot review. The session that owns the
PR then merges it without waiting for the operator.

## Motivation

The operator has given this instruction more than once, and sessions kept
missing it. On 2026-11-01 two PRs (#2296, #2305) merged on green CI and a clean
Copilot review alone, skipping gates 1 and 2. The rule lived only in
conversation, so a new session never saw it.

## Changes in Detail

1. `claude.md`: a short "PR merge gate" section listing the four gates, with a
   link to `agents.md`.
2. `agents.md`: a "PR Merge Gate" section with the full rule: no drafts, the
   four gates in order, the owning session merges, green CI plus Copilot is not
   enough, and findings from gates 1 and 2 restart the later gates.

## Testing Plan

Documentation only; no code or test changes. `bb fmt:md` / markdownlint runs in
CI.

## Deployment Plan

None. Sessions read `claude.md` and `agents.md` when they start.

## Related Issues/PRs

- #2296, #2305: merged without gates 1 and 2; their post-merge reviews are
  tracked in follow-up PRs.

## Not in this PR

- The same rule in the shared standards (`miniforge-standards`), so sessions in
  other repos load it too. That needs push access to that repository.
- `claude.md` is lowercase. Claude Code on case-sensitive filesystems (Linux
  cloud sessions) looks for `CLAUDE.md`, so cloud sessions do not load it.
  Renaming it touches the knowledge loader (`loader.clj`) and a context-pack
  test that name `claude.md`, so it is a separate change.

## Checklist

- [x] No code changes
- [x] No user-facing strings added
