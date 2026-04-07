# PR: Move ETL runtime helpers out of shared workflow and logging

## Summary

This PR moves the remaining ETL-specific execution and lifecycle-event logic out
of shared components and into `workflow-financial-etl`.

After `#304`, the ETL workflow family itself was app-owned, but the ETL runner
and ETL event helper APIs still lived in shared `workflow` and shared
`logging`. This PR finishes that extraction.

## Changes

- Added ETL-owned source namespaces under:
  - `components/workflow-financial-etl/src/ai/miniforge/workflow_financial_etl/`
- Added ETL-owned tests under:
  - `components/workflow-financial-etl/test/ai/miniforge/workflow_financial_etl/`
- Expanded `workflow-financial-etl` deps so it can own:
  - ETL execution helpers
  - ETL lifecycle event helpers
- Removed ETL runner implementation from:
  - `components/workflow/src/ai/miniforge/workflow/etl.clj`
- Removed ETL-specific event helper exports from shared logging:
  - `components/logging/src/ai/miniforge/logging/interface.clj`
- Removed ETL-specific event helper implementation from shared logging:
  - `components/logging/src/ai/miniforge/logging/events/etl.clj`
- Updated `components/workflow-financial-etl/README.md` to document the new
  ownership boundary

## Why

The shared runtime should own generic workflow execution seams, not ETL-specific
pipeline helpers or ETL-specific event constructors.

ETL is now a real product track. The ETL runner and ETL lifecycle events are
part of that product surface and should live with the ETL component the same
way SDLC workflow families and phase implementations now live with
software-factory-owned components.

## Boundary Result

After this PR:

- shared `workflow` owns generic workflow runtime code
- shared `logging` owns generic logging APIs
- `workflow-financial-etl` owns:
  - ETL workflow family resources
  - ETL state-profile resources
  - ETL execution helpers
  - ETL lifecycle event helpers

## Verification

- `clojure -M:dev:test -e "(require 'ai.miniforge.workflow-financial-etl.interface-test)(clojure.test/run-tests
  'ai.miniforge.workflow-financial-etl.interface-test)"`
- `bb test`
- `bb build:cli`
- `bb build:tui`
- `bb pre-commit`
