<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# Fix CLI Artifact And Policy Command Composition

## Overview

This PR continues the standards-remediation work after the broad resolver
cleanup waves by removing symbol-provider indirection from CLI artifact and
policy commands where the components are always present on the product
classpath.

## Layer

CLI adapter cleanup.

The PR does not change artifact or policy domain behavior. It tightens CLI
composition so command namespaces call concrete Polylith interfaces directly.

## Motivation

The CLI optional function registry was still carrying artifact and policy-pack
entries that no longer represent optional product composition. Keeping those
paths behind symbol lookups hides dependency requirements and makes broken
interface calls fail silently. Direct interface calls preserve the existing
fallback behavior while making the dependency boundary explicit.

## Gap Analysis

### Fixed in this PR

- `bases/cli/.../commands/artifact_cmds.clj`
  - Replaced `shared/try-resolve-fn` artifact listing with direct
    `artifact/query` through a configured transit store.
  - Replaced dynamic artifact provenance lookup with direct
    `artifact/get-provenance`.
  - Preserved the existing filesystem fallback paths.

- `bases/cli/.../commands/policy.clj`
  - Replaced `shared/try-resolve-fn` policy listing with direct
    `policy-pack/load-all-packs`.
  - Replaced dynamic installed-pack lookup with direct `policy-pack/load-pack`.
  - Preserved resource and filesystem fallback behavior.

- `bases/cli/.../main.clj`
  - Removed artifact and policy-pack provider registration from the optional
    CLI composition registry.

### Intentionally left in place

- `bases/cli/.../main.clj`
  - `optional-composition-var` remains the explicit product-composition
    boundary for optional web-dashboard / TUI classpath loading.

- `bases/cli/.../commands/fleet.clj`
  - TUI launch resolution still crosses the optional TUI product boundary.

- `bases/cli/.../commands/etl.clj`
  - ETL repository analysis still needs a separate product-boundary decision.

- `bases/cli/.../commands/evidence.clj`
  - Evidence command dynamic lookups need a separate manager/persistence design
    review rather than a mechanical replacement.

## Testing Plan

- [x] `git diff --check`
- [x] Focused Clojure lint for touched CLI source and tests.
- [x] Focused CLI command tests:

  ```bash
  clojure -Sdeps '{:deps {io.github.cognitect-labs/test-runner {:git/tag "v0.5.1" :git/sha "dfb30dd"}}}' -M:dev:test -m cognitect.test-runner -d bases/cli/test -n ai.miniforge.cli.main.commands.artifact-cmds-test -n ai.miniforge.cli.main.commands.policy-test -n ai.miniforge.cli.main-test
  ```

- [x] `bb pre-commit`

## Deployment Plan

Merge normally after CI and review. No migration step is required.

## Rollback Plan

Revert the PR. The change is localized to CLI artifact and policy command
composition.

## Related Issues/PRs

- Builds on the standards remediation waves that landed in:
  - #1221
  - #1222
  - #1228
  - #1230

## Checklist

- [x] Production resolver hotspots reviewed
- [x] Static CLI dependencies used where required
- [x] Optional product-composition boundaries left explicit
- [x] Focused tests passed
- [x] Pre-commit passed
- [ ] PR opened
- [ ] Copilot comments settled
- [ ] All review comments resolved
