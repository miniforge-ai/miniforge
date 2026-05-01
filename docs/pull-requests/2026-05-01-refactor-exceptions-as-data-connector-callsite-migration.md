# refactor: migrate connector callsites to shared validation helpers

## Overview

Migrates seven connector components from inline `require-handle!` / `validate-auth!` patterns to the shared anomaly-returning + throwing variants in `connector` core (which landed in PR #704). Retires 14 of the 158 cleanup-needed sites identified in `work/exception-cleanup-inventory.md`.

This is the second tier of the exceptions-as-data cleanup — the foundation cleanup PR (#704) extracted the helpers; this PR migrates the callsites.

## Motivation

Each connector component (`connector-jira` / `gitlab` / `github` / `excel` / `edgar` / `file` / `sarif`) reimplemented the same `require-handle!` / `validate-auth!` patterns inline before #704. With the shared helpers now available in `connector` core, callers can stop duplicating the validation logic and start participating in the canonical anomaly flow at non-boundary sites while preserving the legacy `ex-info` shape at boundary sites.

The brief was deliberately scoped to handle-lookup and auth-validation patterns. Schema/`validate!`, exhaustive-case fallthroughs, HTTP errors, and config validation are out of scope — those land in subsequent cleanup PRs.

## Base Branch

`main`

## Depends On

- `ai.miniforge.connector.interface/{require-handle, require-handle!, validate-auth, validate-auth!}` — landed in PR #704 (foundation cleanup)
- `ai.miniforge.anomaly` — landed in PR #689

## Layer

Refactor / connector tier. Touches connector implementation files only.

## What This Adds / Changes

Source + test files for seven connectors:

- `components/connector-jira/src/.../impl.clj` + `test/.../impl_test.clj`
- `components/connector-gitlab/src/.../impl.clj` + `test/.../impl_test.clj`
- `components/connector-github/src/.../impl.clj` + `test/.../impl_test.clj`
- `components/connector-excel/src/.../impl.clj` + `test/.../interface_test.clj`
- `components/connector-edgar/src/.../impl.clj` + `test/.../interface_test.clj`
- `components/connector-file/src/.../impl.clj` + `test/.../interface_test.clj`
- `components/connector-sarif/src/.../impl.clj` + `test/.../impl_test.clj`

14 files total. **+311 / -100 lines.** No `deps.edn` changes — every targeted connector already declared `ai.miniforge/connector` as a `:local/root` dep.

## Migration patterns

**Two patterns. Both retire inline validation in favor of shared helpers.**

1. **Handle lookup.** Local `require-handle!` shrinks to a one-liner over `connector/require-handle!`, supplying the connector-specific localized `:message`. Inline `(or (get-handle h) (throw ex-info ...))` is removed.
2. **Auth validation.** Local `validate-auth!` calls the anomaly-returning `connector/validate-auth`, then re-throws with the localized message that interpolates the actual validation errors. Matches the brief's `(if-let [a (connector/validate-auth auth)] a ...)` shape. The protocol method body remains the boundary that throws — `pipeline-runner`'s `try/catch` expects that contract and stays unchanged.

## Inventory delta

**14 of 158 cleanup-needed sites retired.** Across the seven targeted connectors:

| Connector | Retired | Left in place (out of scope) |
|---|---:|---|
| connector-jira | 2 | schema/`validate!`, resource-unknown, site-required |
| connector-gitlab | 2 | schema/`validate!`, resource-unknown, project-required |
| connector-github | 2 | resource-unknown, config-invalid, owner-or-org-required |
| connector-excel | 2 | sheet-not-found, download-failed, url/sheet/columns required |
| connector-edgar | 2 | aggregation-unknown, form-type/user-agent/aggregation-required |
| connector-file | 3 | format/path/file-not-found, format-unsupported |
| connector-sarif | 1 | "Invalid SARIF config" |

The left-in-place sites are config validation, exhaustive-case fallthroughs, HTTP errors, or `schema/validate!` — all out of scope per the brief, which restricts this PR to the `require-handle!` / `validate-auth!` patterns. They land in subsequent cleanup PRs.

## Strata Affected

Per-connector implementation namespaces (`impl.clj` for most; `interface_test.clj` for connectors that test through their public interface). No public connector API changed.

## Testing Plan

- **`bb pre-commit` green:**
  - `lint:clj` — 0 errors. 8 warnings, all pre-existing in the touched files (unused `schema.interface` requires, unused `clojure.string` references, unused binding). None introduced by this PR.
  - `fmt:md` — clean.
  - `test` — **3530 tests / 15746 passes / 0 failures / 0 errors.** Net new assertions: 21 across the seven connectors, covering handle-not-found `ex-info` shape and auth-rejection / auth-success paths for jira / gitlab / github.
  - `test:graalvm` — 6 tests / 479 assertions / 0 failures.

## Deployment Plan

No migration required. Backward-compatible.

- `pipeline-runner` already destructures protocol-method results and wraps in `try/catch Exception` for stage failures. The shared `require-handle!` / `validate-auth!` preserve the legacy `ex-info` shape, so existing CLI/MCP error handling is unchanged.
- New non-boundary call sites (where they exist post-migration) consume the anomaly-returning variants directly.

## Notes / Surprises

1. **`connector-sarif` used a private `(atom {})`** instead of `connector/create-handle-registry`. Migrated it to the shared registry as part of this PR — modest scope creep, but necessary to call the shared helper. Tests don't peek at the atom internals; behavior preserved.
2. **`pipeline-runner` is the architectural boundary, not the connector protocol body.** A strict reading of "boundary call sites use throwing variants" could imply CLI/MCP only. But `pipeline-runner` destructures result maps directly inside a `try/catch Exception` outer wrapper. Returning anomalies there would silently produce empty `:records` instead of `:failed` stage results. So protocol method bodies kept the throw at exit; internal helpers use the anomaly form. Aligns with the "preserve legacy ex-info shape" intent in the brief. Pipeline-runner adapter changes belong to a separate workstream.
3. **Duplicate `connector.interface` requires** — `connector-jira`, `connector-gitlab`, `connector-file`, and `connector-github` had the namespace listed twice in their `ns` form. Removed during the touch.

## Related Issues/PRs

- Built on PR #704 (`refactor: foundation-tier exceptions-as-data cleanup`)
- Built on PR #689 (`feat: add anomaly component`)
- Tracked in PR #691 (`docs: inventory exception/throw sites for cleanup`)
- Companion to PR #708 (`fix: brick-isolation hygiene`)

## Checklist

- [x] All seven connectors migrated to shared helpers
- [x] Inline `require-handle!` / `validate-auth!` patterns removed
- [x] `pipeline-runner` boundary contract preserved (`ex-info` shape unchanged)
- [x] Tests added for handle-not-found / auth-rejection / auth-success paths
- [x] No `deps.edn` changes (all connectors already had the required `:local/root` dep)
- [x] No public API change
- [x] Apache 2 license headers preserved
- [x] `bb pre-commit` green
