<!-- Title: Miniforge.ai -->
<!-- Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai) -->
<!-- Licensed under the Apache License, Version 2.0. -->
# CLI command strata

## Scope and dependencies

Base: main. The evidence export adapter needs to correct positional output-path
routing, but the CLI entry point currently has seven dependency layers. Split
status reporting, optional dashboard construction and version presentation into
focused namespaces before changing that route. No routing changes belong here.

## Design and standards review

Keep the entry point as command adapters, dispatch data and dispatch execution.
Separate status reconstruction from presentation. Keep optional component
resolution and dashboard setup in the composition boundary. Preserve command
output, startup order and optional product composition. Fatal errors and thread
interruption now propagate instead of being swallowed by optional setup/history.
Use named functions instead of nested callbacks and share status test fixtures.

## Verification and merge gates

Run the CLI main and monitoring tests, build relevant CLI packaging, and
exercise packaged commands. Review the extraction diff, including moved
comments and exception paths. Require kondo, Polylith, strata, normal hooks,
settled current-head review and all CI checks before merge. No deployment step
is required. Keep commits within 200 and this PR within 600 reportable lines.

Focused JVM and rebuilt packaged checks pass 21 tests and 74 assertions each.
Tests cover deferred dashboard setup order, missing history, terminal status,
shared reconstruction fixtures, and fatal/interruption propagation.
