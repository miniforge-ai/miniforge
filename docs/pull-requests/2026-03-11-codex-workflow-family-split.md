# refactor: split software-factory workflow families from workflow kernel

## Overview

Moves the SDLC workflow-family resources out of the shared `workflow` component and into a
software-factory app component. The workflow kernel now discovers workflow resources from the
active classpath instead of relying on a shared registry file with hardcoded workflow names.

## Motivation

The previous extraction passes cleaned up runtime state and provider seams, but the workflow
catalog still made software-factory workflows look like kernel defaults. That kept the OSS kernel
framed as an SDLC product instead of a governed workflow engine with multiple workflow families.

This pass makes workflow-family availability a composition concern:

- kernel `workflow` ships only generic/test/demo/ETL workflows
- software-factory workflows are loaded only when the flagship app component is on the classpath
- workflow discovery aggregates all `workflows/*.edn` resources on the classpath instead of reading
  a single shared `workflow-registry.edn`

## Changes in Detail

- Added `components/workflow-software-factory` as a resource-only app component for SDLC workflow
  families:
  - `canonical-sdlc-v1`
  - `lean-sdlc-v1`
  - `nimble-sdlc`
  - `quick-fix`
  - `standard-sdlc`
- Removed those workflow files from the shared `components/workflow/resources/workflows/` directory.
- Deleted `resources/config/workflow-registry.edn`; the registry no longer depends on a shared
  registry file.
- Updated `workflow.loader` and `workflow.registry` to aggregate all `workflows/` resources on the
  classpath, including multiple contributing components and JAR resources.
- Wired the software-factory workflow component into the flagship app composition via:
  - `bases/cli/deps.edn`
  - `projects/miniforge/deps.edn`
  - `projects/miniforge-tui/deps.edn`
  - workspace `:dev` / `:test` aliases in `deps.edn`
- Simplified CLI workflow listing so it uses the workflow interface’s discovered workflow metadata
  instead of a hardcoded list of SDLC filenames.
- Added `workflow.registry-test` to cover classpath-driven workflow discovery and initialization.

## Boundary Impact

- `components/workflow` is now closer to a true kernel workflow family host.
- The autonomous software-factory workflow families are now explicitly app-owned.
- Future non-software apps can contribute their own workflow resources by adding another component
  to the consuming project’s classpath, without touching the kernel catalog.

## Testing Plan

- `clojure -M:dev:test -e "(require '[clojure.test :as t] 'ai.miniforge.workflow.registry-test
  'ai.miniforge.workflow.loader-test 'ai.miniforge.cli.workflow-selector-test) ..."`
- `bb pre-commit`
- `bb test:integration`
- `bb build:cli`
- `bb build:tui`

## Deployment Plan

Merge normally. This is a refactor plus workflow-resource relocation; no data migration is needed.

## Related Issues/PRs

- Follow-up to PR #291
- Follow-up to PR #292
- Follow-up to PR #294

## Checklist

- [x] Move software-factory workflow resources behind app composition
- [x] Remove the shared workflow registry file
- [x] Discover workflow families from the active classpath
- [x] Keep kernel/demo/ETL workflows in the shared workflow component
- [x] Add regression coverage for classpath-based workflow discovery
- [x] Re-run `bb pre-commit`
- [x] Re-run `bb test:integration`
- [x] Re-run `bb build:cli`
- [x] Re-run `bb build:tui`
