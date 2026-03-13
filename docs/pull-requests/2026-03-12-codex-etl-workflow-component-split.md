# PR: Move ETL workflow family into app-owned component

## Summary

This PR moves the shipped `financial-etl` workflow definition out of the shared
`workflow` component and into the ETL-owned `workflow-financial-etl`
component.

The runtime seam for app-owned workflow families is already in place. This PR
finishes the ownership move for ETL so the finance product is treated like a
real application layer rather than a kernel demo.

## Changes

- Added `financial-etl-v1.0.0.edn` under:
  - `components/workflow-financial-etl/resources/workflows/`
- Removed `financial-etl-v1.0.0.edn` from:
  - `components/workflow/resources/workflows/`
- Expanded `components/workflow-financial-etl/README.md` to document ownership
  of:
  - ETL workflow family resources
  - ETL state-profile resources
- Updated shared workflow catalog docs so they no longer describe ETL as a
  shared workflow family:
  - `components/workflow/resources/workflows/README.md`
  - `docs/architecture/workflow-component.md`
  - `docs/architecture/live-workflow-configuration.md`
- Added explicit ETL product composition to:
  - `projects/miniforge/deps.edn`
  - `projects/miniforge-tui/deps.edn`

## Why

The previous seam work moved software-factory workflows, phases, chains, and
state profiles out of the kernel-facing components. ETL still leaked one level
deeper because its shipped workflow family remained in the shared workflow
catalog.

ETL is now a real product track, not just a proof workflow. Its workflow family
should therefore be owned by the ETL product component, while the shared
runtime remains responsible only for generic workflow loading and execution.

## Boundary Result

After this PR:

- shared `workflow` owns:
  - generic runtime code
  - shared example and validation workflows
- `workflow-software-factory` owns:
  - SDLC workflow families
- `workflow-financial-etl` owns:
  - ETL workflow family
  - ETL state-profile configuration

## Verification

- `clojure -M:dev:test -e "(require 'ai.miniforge.workflow.registry-test)(clojure.test/run-tests
  'ai.miniforge.workflow.registry-test)"`
- `clojure -M:dev:test -e "(require 'ai.miniforge.workflow.loader-test)(clojure.test/run-tests
  'ai.miniforge.workflow.loader-test)"`
- `bb test`
- `bb build:cli`
- `bb build:tui`
- `bb pre-commit`
