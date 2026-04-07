<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# PR: Move app-owned state-profile config out of shared workflow

## Summary

This PR removes the remaining concrete workflow state-profile resources from the
shared `workflow` component.

The shared workflow layer keeps only the classpath-loading helper. The concrete
`software-factory` profile now lives under `workflow-software-factory`, and the
`etl` profile now lives under a new ETL-owned resource component.

## Changes

- Moved `software-factory` state-profile resources from `components/workflow/`
  to `components/workflow-software-factory/`
- Moved the `etl` state-profile resource into
  `components/workflow-financial-etl/`
- Updated workflow state-profile loading to merge all matching classpath
  registry resources instead of reading only the first one
- Tightened `workflow.state-profile` so missing app profiles return `nil`
  instead of silently falling back
- Updated workflow tests to assert app-owned loading and explicit missing
  profiles
- Added a test-only dependency from `workflow` to `workflow-software-factory`
  and `workflow-financial-etl` so the composition seam is explicit in component
  tests
- Added `workflow-financial-etl` to the CLI app deps so both shipped products
  compose into the app runtime
- Updated the software-factory README to document ownership of state-profile
  config
- Added an ETL-owned README for the financial ETL resource component

## Why

These profile definitions are project-specific configuration, not kernel data.
Leaving them under the shared `workflow` component kept software-factory and ETL
ownership blurred even after the earlier seam extraction work.

Because ETL is now a real product track, the right fix is not deletion. It is
to move ETL configuration into an ETL-owned component and make classpath
composition load both product layers cleanly.

## Verification

- `bb pre-commit`
