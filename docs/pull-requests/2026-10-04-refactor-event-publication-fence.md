<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# refactor: Isolate atomic publication fencing

## Purpose

Separate workflow admission and in-flight ownership from event construction and
delivery. This application primitive keeps the upcoming acknowledged publisher
within three genuine strata without nesting its validation and rejection paths.

Base: main after #1985. No dependency on the timeline cleanup.

## Design

The `acquire-state` transition is pure: refusal returns the identical state;
admission increments the counter. The successful before/after pair
from `swap-vals!` determines ownership without side effects inside a retried CAS.
An acquired slot is released in finally, including when publication throws.
The public publisher keeps its existing sequencing and delivery behavior.
Quiesce rejection uses a localized message and one shared result constructor.

## Acceptance

Existing fence, drain, failure and race tests must pass after moving their private
references. Add concurrent acquisition coverage, run all event-stream consumers,
rebuild the CLI, verify packaged behavior, and review the actual changed diff.
Normal signed hooks, standards scan, exact-head review and all CI gate the merge.
Preserve the branch and worktree. This PR makes no durable-publication claim.
