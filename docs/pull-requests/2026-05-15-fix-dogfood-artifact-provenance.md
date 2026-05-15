# Fix: Dogfood Artifact Provenance

## Overview

This stacked PR fixes the artifact extraction failure that surfaced after the
checkpoint resume repair. When agents do not submit `artifact.edn`, Miniforge now
treats the promoted worktree or container workspace as valid artifact
provenance.

## Motivation

Dogfood resume reached implement/review correctly, then failed again because the
fallback artifact collector only looked at the latest agent-session delta. In
repair loops, the relevant implementation can already be committed or otherwise
present in the promoted worktree, while the latest turn may touch only one file
or no files. That made valid task output look like `:curator/no-files-written`.

Agents still should submit artifacts explicitly when they can, but file state in
the task worktree/container is authoritative enough for code artifacts because
the environment itself is what Miniforge promotes.

## Changes in Detail

- Added promoted-worktree artifact collection that diffs the task workspace
  against captured base refs and merges current dirty or untracked files.
- Captured base SHA metadata when creating local worktree and OCI container
  execution environments.
- Updated implementer and curator fallback extraction to prefer promoted
  worktree/container artifacts before using the old per-session delta fallback.
- Updated review artifact resolution so downstream phases can recover file
  artifacts from the worktree even when the implement phase missed explicit
  artifact submission.
- Covered committed task diff plus dirty-file fallback behavior.

## Stacking

This PR is stacked on:

- `fix/dogfood-resume-checkpoint-repair`
- PR #875

The resume/checkpoint repair should merge first. This PR addresses the next
blocker discovered by that dogfood pass.

## Testing Plan

- Targeted artifact/review tests: 14 tests, 45 assertions, 0 failures, 0 errors.
- Broader agent/factory/executor targeted tests: 123 tests, 377 assertions, 0
  failures, 0 errors.
- `bb test` passed across the stable-derived test plan with only existing
  unresolved-var warnings.

## Deployment Plan

Merge after PR #875. No migration is required. The change only broadens fallback
artifact extraction when explicit MCP artifact submission is missing.

## Checklist

- [x] Explicit artifacts still win when present.
- [x] Files on disk are treated as valid fallback artifacts.
- [x] Container/worktree base refs are captured for task diffing.
- [x] Dirty and untracked files remain included when base diffing fails.
