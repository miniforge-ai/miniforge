<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# Fix: Dogfood Resume Checkpoint Repair

## Overview

This PR fixes resume behavior discovered while dogfooding the highest-priority
event-log tool visibility spec. Resumed software-factory runs now restart from
the first unfinished or repair-required phase instead of treating stale manifest
entries as authoritative completion.

## Motivation

Dogfood run `3927baf8-c9db-44d3-b5fb-5a1552dbe554` repeatedly resumed into the
wrong phase after review-driven repair and terminal release failure. The
checkpoint manifest could preserve failed or stale phase names, and the resume
trimmer removed completed phases globally rather than only trimming the completed
prefix.

## Changes in Detail

- Resume now treats only successful phase results as completed.
- Review `changes-requested` and rejected decisions block completion so repair
  resumes at `implement`.
- Pipeline trimming now removes only the leading completed prefix, preserving
  later phases after the first incomplete phase.
- Checkpoint manifest persistence now merges existing phase checkpoint paths so
  partial resumed pipelines do not shrink historical checkpoint metadata.
- Runner now persists the terminal execution snapshot before publishing the
  completed workflow event.
- Release `zero-files` responses now fail deterministically instead of entering
  a retry loop.

## Dogfood Notes

- Resumed checkpoint: `3927baf8-c9db-44d3-b5fb-5a1552dbe554`
- After these fixes, the run resumed from `implement`, advanced through
  `verify` and `review`, and correctly returned to `implement` after review
  changes were requested.
- The next blocker is artifact extraction when agents do not submit
  `artifact.edn`; the worktree/container contents should be valid provenance.

## Testing Plan

- `clojure -M:dev:test -e ...` for workflow resume, checkpoint store, and runner
  tests: 51 tests, 155 assertions, 0 failures, 0 errors.
- `bb test` passed before the final narrow runner test addition and after the
  resume trim correction.

## Deployment Plan

Merge normally after review. These changes affect resume/checkpoint behavior and
software-factory release failure classification only.

## Related Issues/PRs

- Dogfood target: `work/event-log-tool-visibility.spec.edn`
- Follow-up bug: file-on-disk artifact provenance when MCP artifact submission is
  missing.

## Checklist

- [x] Resume from checkpoint restarts at the correct repair phase.
- [x] Failed or review-blocked phases are not treated as complete.
- [x] Partial resumed pipeline manifests preserve prior checkpoint paths.
- [x] Terminal workflow snapshots are persisted.
- [ ] File-on-disk artifact provenance follow-up is fixed.
