<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# refactor: remove resolver hotspots from standards remediation wave 50

## Overview

This PR continues the standards-remediation work that removes production
`requiring-resolve`, `resolve`, `ns-resolve`, and function-local `require`
anti-patterns where static component dependencies already exist.

The slice focuses on the remaining production hotspots identified after
remediation waves #1221, #1222, and #1228:

- CLI optional composition registry
- web-dashboard PR train and repo DAG calls
- policy-pack knowledge-safety schema validation
- artifact transit-store logging

## Layer

Application / adapter cleanup.

The PR does not introduce new domain behavior. It tightens composition and
dependency boundaries in CLI/dashboard adapters and one policy validation path.

## Motivation

Dynamic var resolution hid real dependency requirements and made failures
silent. That was useful only for product composition boundaries where the
entire component may be absent from a product classpath. Everywhere else,
direct Polylith interfaces make dependencies explicit, preserve compile-time
checks, and fail in the component that owns the broken call.

## Gap Analysis

### Fixed in this PR

- `bases/cli/.../main.clj`
  - Replaced dynamic lookup for artifact and policy-pack registrations with
    direct interface calls.
  - Replaced dynamic lookup of PR train and repo DAG manager constructors with
    direct interface calls.
  - Renamed the remaining helper to `optional-composition-var` and documented
    that it is only for optional web-dashboard / TUI product composition.

- `components/web-dashboard/.../state/trains.clj`
  - Replaced `core/safe-call` dispatch to PR train and repo DAG with direct
    `pr-train` and `repo-dag` interface calls.
  - Added explicit component dependencies in `components/web-dashboard/deps.edn`.
  - Updated tests to stub concrete interface vars instead of the deleted
    resolver helper.

- `components/web-dashboard/.../state/core.clj`
  - Removed the generic `safe-call` resolver helper.

- `components/policy-pack/.../knowledge_safety.clj`
  - Replaced function-local schema namespace loading and `ns-resolve` with a
    static `policy-pack.schema` require.

- `components/artifact/.../transit_store.clj`
  - Replaced function-local logging require and resolved `info` var with a
    static logging interface require.

### Intentionally left in place

- `bases/cli/.../main.clj`
  - `optional-composition-var` still uses `require` + `ns-resolve` for the
    product-composition boundary where `miniforge-core` can load the CLI
    without web-dashboard or TUI components on the classpath.

- Generic namespace loaders
  - Config-driven phase namespace loading and test namespace discovery remain
    dynamic by design.

- Rich comments and tests
  - Remaining scan hits are rich-comment examples, docstrings, or test-only
    reflective checks. They are outside this production cleanup slice.

## Changes in Detail

- Added direct CLI requires for `artifact`, `policy-pack`, `pr-train`, and
  `repo-dag`.
- Narrowed the CLI optional late-binding helper to explicit optional
  composition.
- Removed dashboard `safe-call` and replaced its call sites with direct
  interfaces.
- Moved dashboard train/DAG fallback boundaries into small `try+`-based helper
  functions and kept cached wrappers thin.
- Normalized request-string train actions to app-local keywords before
  dispatching PR train operations.
- Added `pr-train` and `repo-dag` as web-dashboard component dependencies.
- Updated dashboard train tests to stub concrete `pr-train` functions.
- Replaced policy-pack schema validation resolver with `schema/validate-pack`.
- Replaced artifact transit-store logging resolver with `log/info`.

## Code Review Rigor Notes

- Happy path traced: `miniforge web` creates an event stream, creates PR train
  and repo DAG managers via direct interfaces, then passes both managers to the
  optional web-dashboard launcher. The only nil path that remains is the
  existing caught manager-construction failure, which already prints the
  operator warning and passes nil to the dashboard.
- Bootstrap order: dashboard state now requires `pr-train` and `repo-dag` at
  namespace load time, so missing dependencies fail during component loading
  rather than silently returning nil from `safe-call`.
- Default path: no manager configured still returns empty dashboard train/DAG
  views as before. A configured manager now uses the explicit interface.
- Refactor scrutiny: the dashboard sync mutation order is preserved:
  add PRs, remove PRs, sync statuses, link PRs. Regression coverage asserts
  this order with concrete interface stubs.

## Testing Plan

- [x] `git diff --check`
- [x] `bb poly:check`
- [x] Focused dashboard train state test:
  `clojure -M:dev:test -e "(require 'ai.miniforge.web-dashboard.state.trains-test) (clojure.test/run-tests 'ai.miniforge.web-dashboard.state.trains-test)"`
- [x] Focused review-comment regression tests:
  `clojure -Sdeps '{:deps {io.github.cognitect-labs/test-runner {:git/tag "v0.5.1" :git/sha "dfb30dd"}}}' -M:dev:test -m
  cognitect.test-runner -d components/web-dashboard/test -d bases/cli/test -n
  ai.miniforge.web-dashboard.state.trains-test -n ai.miniforge.cli.main-test`
- [x] `bb test`
- [x] `bb pre-commit`

## Deployment Plan

Merge normally after CI and review. This is a code hygiene change with no
migration step.

## Rollback Plan

Revert the PR. The change is localized to CLI composition, web-dashboard train
state, policy-pack validation, and artifact transit-store logging.

## Related Issues/PRs

- This PR: #1230
- Builds on the standards remediation waves that landed in:
  - #1221
  - #1222
  - #1228

## Checklist

- [x] Production resolver hotspots reviewed
- [x] Static dependencies added where required
- [x] Optional product-composition boundary documented
- [x] Tests updated for direct interface calls
- [x] Broad test suite passed
- [x] Pre-commit passed
- [x] PR opened
- [x] Copilot comments settled
- [x] All review comments resolved
