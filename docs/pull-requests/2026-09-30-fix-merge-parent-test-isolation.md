<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# fix: isolate merge-parent integration fixtures

## Overview and motivation

Sibling JVM runs currently use the same literal workflow ID in merge-parent
fixtures. Identical Git inputs can then address the same temporary merge worktree,
whose cleanup can interfere with the other fixture. The operator observed flakes
under concurrent sibling test runs; this shared-path mechanism is a plausible cause.

## Changes and standards

Give each repository fixture its own run ID and retain it throughout that fixture,
including replay assertions. Assert that independent fixture runs have distinct
scratch paths even for identical task and input identities. Production merge logic
is unchanged. This is a small test-isolation PR based on main with no dependencies.
Extract Git setup primitives into a test-support namespace so fixtures, contexts,
and scenarios fit three honest strata. Reuse the context factory for the empty
registry case instead of duplicating its map.

## Testing plan

The focused merge-parent integration suite passed serially: 14 tests / 54 assertions.
Kondo has no warnings/errors and the standards scan has no findings on the changed
test file. Run normal hooks, review, and CI. Do not claim general cross-process safety or resume parallel
JVM suites without separate verification.

## Deployment and related work

No runtime deployment or migration. This supports the spec-completion verification
queue by removing shared fixture identity; it does not change merge semantics.

## Checklist

- [x] Focused regression and existing integration tests pass
- [ ] Standards, review, and CI settle
