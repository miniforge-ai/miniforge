<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix: Clarify Clojure map-default semantics wave 1

## Overview

Resolve a coherent subset of the remaining Dewey 210 map-default findings after reviewing each call site for explicit
nil behavior.

## Motivation

The scanner suggests replacing `(or (key-fn m) fallback)` with three-argument `get`, but those forms differ when the
map contains the key with a nil or false value. Each change therefore needs contract and test review rather than a bulk
rewrite.

## Changes in Detail

- Replace five LLM token-count fallback expressions with intent-named helpers that use explicit map lookup.
- Preserve the established contract that both missing and explicitly nil token counts mean zero.
- Add regression coverage for explicit nil values in cost estimation and context-window accounting.

## Testing Plan

- Run focused component tests for every touched behavior.
- Run the Dewey 210 scanner and verify only intentionally deferred findings remain.
- Run `bb pre-commit`.

## Deployment Plan

Merge normally after CI and review. This PR must remain independently mergeable and behaviorally explicit.

## Related Issues/PRs

- Base branch: `main`.
- Depends on: none.
- Follows #1419 and #1425.

## Checklist

- [x] Review nil and false semantics for every selected finding.
- [x] Add regression coverage for changed behavior.
- [x] Pass focused scans and tests.
- [ ] Pass CI and resolve review comments.
