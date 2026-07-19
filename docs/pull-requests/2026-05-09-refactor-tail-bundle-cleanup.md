<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# refactor: exceptions-as-data cleanup tail bundle (policy-pack + tool-registry)

## Overview

Migrates the remaining `:cleanup-needed` throw sites in two small
components into a single PR — bundled because per-component count
(6 + 4) is too small to justify two separate PRs and they share no
coupling.

- `policy-pack/registry.clj` — 6 sites (validation, import,
  export-pack)
- `tool-registry/registry.clj` — 4 sites (register, update)

## Motivation

Per `work/exception-cleanup-inventory.md`, `policy-pack` and
`tool-registry` carried the remaining concentrated `:cleanup-needed`
clusters in the < 5-site tier. Bundling matches the kill-the-deprecation
pattern (PRs #777, #797, #799, #800, #801, #846, #854) — same shape,
applied without redesign.

## Base Branch

`main`

## Depends On

- `ai.miniforge.anomaly` (merged) — anomaly type vocabulary
- `ai.miniforge.response` (merged) — slingshot `throw-anomaly!`

## Layer

Refactor / per-component cleanup tier.

## Scope decision: bb-data-plane-http excluded

The audit also listed `bb-data-plane-http/core.clj` (4 sites) under
`:cleanup-needed`. That component lives in the `bb-utils` GraalVM-
compiled project, where pulling `ai.miniforge/anomaly` (depends on
malli) and `ai.miniforge/response` (depends on slingshot) into the
classpath isn't free — both deps carry transitive weight that would
need explicit GraalVM-compat validation. The 4 sites there are
boundary-shaped already (data-plane process death, HTTP failures
surfacing to a CLI). Reclassifying as `:fatal-only` for now and
deferring the proper migration to a follow-up that does the GraalVM
work properly.

## What This Adds / Changes

`components/policy-pack/deps.edn`:

- Adds `:local/root` deps on `ai.miniforge/anomaly` and
  `ai.miniforge/response`.

`components/policy-pack/src/.../registry.clj`:

- 6 throw sites migrated to `response/throw-anomaly!`:
  - `register-pack` — schema invalid → `:anomalies/incorrect`
  - `import-pack` — string source not implemented →
    `:anomalies/unsupported`
  - `export-pack` — JSON format → `:anomalies/unsupported`
  - `export-pack` — directory format → `:anomalies/unsupported`
  - `export-pack` — unknown format → `:anomalies/incorrect`
  - `export-pack` — pack not found → `:anomalies/not-found`

`components/tool-registry/deps.edn`:

- Adds `:local/root` deps on `ai.miniforge/anomaly` and
  `ai.miniforge/response`.

`components/tool-registry/src/.../registry.clj`:

- 4 throw sites migrated to `response/throw-anomaly!`:
  - `register-tool` — schema invalid → `:anomalies/incorrect`
  - `register-tool` — id shape invalid → `:anomalies/incorrect`
  - `update-tool` — tool not found → `:anomalies/not-found`
  - `update-tool` — schema invalid on update → `:anomalies/incorrect`

`components/policy-pack/test/.../anomaly/` (new):

- `registry_anomaly_test.clj` — 7 tests across the 6 boundary sites
  (register-pack, import-pack, export-pack × 4 formats).

`components/tool-registry/test/.../anomaly/` (new):

- `registry_anomaly_test.clj` — 5 tests across the 4 boundary sites
  (register-tool, update-tool, ex-data shape).

## Per-site classification

### `policy-pack/registry.clj`

| Site (line) | Fn | Anomaly category |
|------------:|----|------------------|
| 201 | `register-pack` (schema invalid) | `:anomalies/incorrect` |
| 259 | `import-pack` (string source) | `:anomalies/unsupported` |
| 267 | `export-pack` (:json) | `:anomalies/unsupported` |
| 268 | `export-pack` (:directory) | `:anomalies/unsupported` |
| 269 | `export-pack` (unknown format) | `:anomalies/incorrect` |
| 270 | `export-pack` (pack missing) | `:anomalies/not-found` |

### `tool-registry/registry.clj`

| Site (line) | Fn | Anomaly category |
|------------:|----|------------------|
| 53 | `register-tool` (schema invalid) | `:anomalies/incorrect` |
| 57 | `register-tool` (bad id shape) | `:anomalies/incorrect` |
| 114 | `update-tool` (tool not found) | `:anomalies/not-found` |
| 118 | `update-tool` (schema invalid) | `:anomalies/incorrect` |

## Strata Affected

- `ai.miniforge.policy-pack.registry` — registry CRUD cleanup
- `ai.miniforge.tool-registry.registry` — registry CRUD cleanup
- New `ai.miniforge.policy-pack.anomaly.*` test namespace
- New `ai.miniforge.tool-registry.anomaly.*` test namespace

## Testing Plan

- New `anomaly.*` test files: 12 tests across the two components.
- Existing component tests retained — `ExceptionInfo`-matching tests
  continue to pass because `response/throw-anomaly!` raises
  `ExceptionInfo` with the canonical message preserved.

## Deployment Plan

No migration. External callers continue to see the same
`ExceptionInfo` shape via boundary throws.

## Notes

- **No drive-by refactors.** Other patterns flagged in the audit
  (e.g. `requiring-resolve` smells elsewhere) are out of scope.
- **bb-data-plane-http deferred.** Documented above — the 4 sites
  there require GraalVM-compat work that's a separate cleanup pass.

## Related Issues/PRs

- Built on PR #777 (kill-the-deprecation precedent)
- Companion to Wave 7 + 8 cleanup PRs — operator (#797),
  spec-parser (#799), agent (#800), task (#801), pr-lifecycle
  (#846), event-stream (#854)
- Tracked in PR #691 (`work/exception-cleanup-inventory.md`)

## Checklist

- [x] All 10 in-scope `:cleanup-needed` sites retired
- [x] bb-data-plane-http deferral documented and rationalized
- [x] Single API per site (`response/throw-anomaly!`)
- [x] Decomposed test files (two)
- [x] No new throws in anomaly-returning code paths
- [x] External caller contracts preserved
- [x] Apache 2 license headers preserved
- [x] `deps.edn` additions explicit and minimal
