# Fix CLI ETL Repo Direct Analysis

## Overview

This PR removes the dead optional `etl-pipe` provider lookup from `mf etl repo`.
The command now calls the direct `repo-analyzer` interface path that was already
required by the namespace.

## Layer

CLI command composition cleanup.

## Motivation

`mf etl repo` previously attempted to call
`ai.miniforge.etl-pipe.interface/etl-repo` through the optional CLI provider
registry. That component is not present in the workspace, so the command always
silently degraded to the fallback path. The fallback is the real implementation
available in this product slice, so the command should name it directly.

## Changes in Detail

- Replaced the optional `etl-pipe` lookup with a direct `repo-analyzer` command
  path.
- Renamed the private fallback helper to describe the actual repository
  analysis behavior.
- Updated the user-facing ETL note so it does not tell users to install a
  nonexistent component.
- Added a regression test that fails if `etl repo` touches the optional
  provider registry for valid repo URLs.

## Gap Analysis

### Fixed in this PR

- `bases/cli/.../commands/etl.clj`
  - Removed dead optional provider lookup for `etl-pipe`.

### Intentionally left in place

- The CLI optional provider registry still exists for true product composition
  boundaries such as TUI availability.
- Other CLI optional provider removals are handled in separate remediation PRs
  to keep review scope small.

## Testing Plan

- [x] `clj-kondo --lint bases/cli/src/ai/miniforge/cli/main/commands/etl.clj bases/cli/test/ai/miniforge/cli/main/commands/etl_test.clj`
- [x] `clojure -Sdeps '{:deps {io.github.cognitect-labs/test-runner {:git/tag "v0.5.1" :git/sha "dfb30dd"}}}'
  -M:dev:test -m cognitect.test-runner -d bases/cli/test -n ai.miniforge.cli.main.commands.etl-test`
- [x] `git diff --check`
- [x] `bb pre-commit`

## Deployment Plan

Merge normally after CI and review. No migration is required.

## Rollback Plan

Revert the PR.

## Related Issues/PRs

- Continues the standards remediation series after #1233.

## Checklist

- [x] Dead optional provider removed
- [x] Direct interface path covered by tests
- [x] Focused tests passed
- [x] Pre-commit passed
- [ ] PR opened
- [ ] Copilot comments settled
- [ ] All review comments resolved
