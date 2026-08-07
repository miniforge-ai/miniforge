<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: decompose GitHub conversation workflow

## Summary

Extracts reply-and-resolution orchestration from the five-layer GitHub
provider namespace before N7 merge enforcement adds provider readback.

## Changes

- Move the high-level fix-link workflow into a three-layer
  `github-conversation` namespace.
- Preserve the public PR-lifecycle API, telemetry, and result behavior.
- Keep reply-only mode functional when automatic resolution is disabled.
- Pass resolution identifiers as GraphQL variables.
- Preserve provider failure messages as strings and attach structured causes.
- Remove the unused GraphQL mutation wrapper.

## Standards review

- `github.clj` drops from five strata to at most three.
- Repeated successful reply maps use one `reply-outcome` factory.
- Provider transport and conversation orchestration remain separate concerns.
- PR budget: 305 / 600 reportable lines; every commit is at most 200.
- Kondo, Polylith, stratum lint, and the standards baseline are clean.

## Verification

- PR-lifecycle component passes in all three project compositions.
- Conversation behavior: 4 tests / 9 assertions.
- Pre-commit smoke: 339 tests / 1,285 assertions.
- GraalVM/Babashka compatibility: 8 tests / 606 assertions.

## Follow-up

PR #1704 will rebase onto this seam and contain only provider-backed review
thread readback and its fail-closed validation.
