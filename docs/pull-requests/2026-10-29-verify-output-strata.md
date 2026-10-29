<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: Separate verify output parsing and lifecycle registration

## Behavior and design

Separate test-output parsing, bounded failure evidence, result construction, and phase registration.
Each namespace has at most three inferred strata.
Keep the public verify runner Vars and the lifecycle's registration timing.
Remove the unused private verdict declaration; preserve actual verdict selection.
Use a shared result constructor and a message-catalog entry for the bounded failure-name suffix.

Host and capsule process execution remain in the existing lifecycle namespace for a follow-up.

## Verification

The existing verify suites pass 32 tests and 104 assertions.
A one-time comparison against the original parser and summary functions passes 120 cases and 360 assertions.
Cases cover Clojure totals, rollup lines, bounded failure blocks, Cargo, missing summaries, preview truncation, and
timeout summaries.
Run affected consumers, normal hooks, standards, review, and CI before merging.

## Deployment

No data-format or configuration migration.
