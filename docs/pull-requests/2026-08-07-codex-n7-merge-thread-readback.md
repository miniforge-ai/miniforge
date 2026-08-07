<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: add provider-backed merge thread readback

## Summary

Adds the GitHub provider read required for N7 merge enforcement. Review-thread
state is fully paginated, validated, and reported through the existing
PR-lifecycle provider API.

## Changes

- Read every GitHub review-thread page and report the exact unresolved count.
- Resolve a REST review comment or reply through its root comment and paginate
  thread roots until the corresponding GraphQL thread is found.
- Reject incomplete provider shapes and missing or repeated page cursors.
- Parse SSH and HTTPS GitHub remotes with optional ports, trailing slashes, and
  dotted repository names without exposing invalid remote values in errors.
- Decompose page mechanics and readback orchestration into focused namespaces
  while preserving the public `github.clj` API.

## Standards review

- Provider transport, response parsing, pagination, and API delegation are
  separate concerns with downward-only dependencies.
- Each changed namespace has at most three strata and each function has one
  primary responsibility.
- Shared response builders remove repeated provider maps from the tests.
- PR budget: 389 / 600 reportable lines; every commit is at most 200.
- Kondo, Polylith, stratum lint, and the changed-code standards baseline are
  clean.

## Verification

- PR-lifecycle component passes in all three project compositions.
- GitHub provider behavior: 16 tests / 48 assertions.
- Pre-commit smoke: 339 tests / 1,285 assertions.
- GraalVM/Babashka compatibility: 8 tests / 606 assertions.
- Standards scan matches the repository baseline: 73 findings, including six
  manual-review findings; no new violations.

## Follow-up

The next N7 PR consumes `unresolved-review-threads` from merge readiness while
decomposing the existing merge control flow. Grant and DecisionEnvelope
enforcement follows that structural seam.
