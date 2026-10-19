<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# refactor: Compose the stratified workflow runner

## Overview

Replace the runner's duplicated implementation with the extracted preparation,
callback, outcome, iteration, and bounded-loop vocabulary. Preserve public names
and arities, and supply nested execution explicitly to eliminate the context/runner
dependency cycle. This prepares the lifecycle publication path for durable receipts.

## Layer and dependencies

Application integration. Based on main after the workflow-runner-vocabulary
prerequisite merged as #2049. The helper namespaces depend downward and never import
the runner. The public context-builder supplies the runner as an explicit port.

## Changes and compatibility

- Keep the public runner API as aliases and a context-builder wrapper.
- Preserve lifecycle ordering, cleanup, retry limits, and supervision.
- Move test instrumentation to the implementation owner for the extracted loop.
- Check nested execution retains the public runner function identity.
- Inherit the prerequisite's explicit executor protocol check and shared failures.

## Verification

All three workflow consumer suites passed after the final helper refinements.
The rebuilt CLI package passed 37 tests and 101 assertions covering runner execution,
initial context, iteration, callbacks, and OPSV finalization and terminal recovery.
Packaged tests ran outside the repository with test-only support and fixtures.
Before committing, finish lint, inferred-strata, and composition gates; audit the
committed diff against pinned standards before pushing.

## Commit discipline

The atomic removal and wiring exceeds the normal 200-reportable-line commit limit.
The user approved one integration commit of up to 494 reportable lines on 2026-10-19.
The actual integration is 492 reportable lines. The exception covers only commit
size; signing, hooks, tests, standards review, PR review, and CI remain required.

## Deployment

This PR does not enable the current journal by default or establish fail-closed
lifecycle receipts. Those behavior changes follow separately after this decomposition.
