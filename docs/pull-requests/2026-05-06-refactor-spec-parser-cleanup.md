# refactor: exceptions-as-data cleanup of spec-parser

## Overview

Migrates every throw site in `components/spec-parser/src/.../core.clj` (7 sites) to anomaly returns. Boundary escalation lives in `parse-spec-file` only — anomalies rethrown as `ex-info` with `:anomaly/type` tagged in `ex-data` so existing slingshot callers (CLI `run`, task-executor pre-flight) stay green.

Mirrors the kill-the-deprecation pattern from PR #777. Single API per site.

## Motivation

Per `work/exception-cleanup-inventory.md`, `spec-parser` had 6 `:cleanup-needed` sites in `core.clj` plus 2 `:fatal-only` markers that got minimal cleanup pass-through (no new throw sites in anomaly-returning paths).

## Base Branch

`main`

## Depends On

- `ai.miniforge.anomaly` (merged) — anomaly type vocabulary and constructor

## Layer

Refactor / per-component cleanup tier.

## What This Adds / Changes

`components/spec-parser/deps.edn`:

- Adds `:local/root` deps on `ai.miniforge/anomaly` (and explicit `ai.miniforge/knowledge` since `core.clj` already required it but the dep was missing from the component's own deps.edn — pre-existing latent gap).

`components/spec-parser/src/.../core.clj`:

- Each thrower replaced with anomaly-returning logic.
- Private helpers introduced: `escalate!`, `file-not-found-anomaly`, `spec-shape-anomaly`, `assemble-normalized-spec`.
- `parse-spec-file` is the single boundary site that inlines `escalate!` to rethrow anomalies as `ex-info` with `:anomaly/type` tagged.

`components/spec-parser/test/.../interface_test.clj`:

- `validation-errors-test` updated to assert anomaly-returns (kill-the-deprecation: same interface name, new contract).

`components/spec-parser/test/.../anomaly/` (new):

- Six new decomposed test files, one per fn: `detect_format_test.clj`, `parse_edn_test.clj`, `parse_json_test.clj`, `normalize_spec_test.clj`, `parse_content_test.clj`, `parse_spec_file_test.clj`. Each covers happy path + failure path + boundary escalation through `parse-spec-file`.

## Per-site classification

| Site (line) | Fn | Anomaly type | Rationale |
|------------:|----|--------------|-----------|
| 46 | `detect-format` (unsupported ext) | `:invalid-input` | caller-supplied path |
| 65 | `parse-edn` (malformed) | `:invalid-input` | caller-supplied content |
| 74 | `parse-json` (malformed) | `:invalid-input` | caller-supplied content |
| 190 | `normalize-spec` (non-map) | `:invalid-input` | caller-supplied shape |
| 194 | `normalize-spec` (no `:spec/title`) | `:invalid-input` | caller-supplied shape |
| 198 | `normalize-spec` (no `:spec/desc`) | `:invalid-input` | caller-supplied shape |
| 237 | `parse-spec-file` (file-not-found) | `:not-found` | caller-supplied path |
| 55 | `parse-yaml` (placeholder) | `:unsupported` | minimal cleanup |
| 170 | `parse-content` (unknown format) | `:fault` | registry programmer error, exhaustive |

## Strata Affected

- `ai.miniforge.spec-parser.core` — every thrower migrated; private helpers added; `parse-spec-file` is the boundary.
- `ai.miniforge.spec-parser.interface-test` — one assertion swap.

## Testing Plan

- `bb pre-commit` green: lint:clj clean, fmt:md clean, test **5129 tests / 23341 passes / 0 failures / 0 errors**, GraalVM compat clean.
- One transient `bb_proc.core_test/test-run!-returns-result-on-zero-exit` flake on a non-final pre-commit run; cleared on the run that landed the commit. Unrelated to spec-parser.

## Deployment Plan

No migration. External slingshot callers continue to see the same `ex-info` shape via the `parse-spec-file` boundary; in-component anomaly returns are additive surface for new callers.

## Notes

- **`spec-parser/deps.edn` was implicitly relying on `ai.miniforge/knowledge`** via the polylith workspace — `core.clj` required it but the dep was missing from the component's own `deps.edn`. Added it explicitly. Pre-existing latent gap, not caused by this refactor.
- **Commit budget**: tripped at 447 lines (ceiling 200). Override invoked per the precedent set by PRs #758 / #769 / #777 / #787 — cleanup is single-purpose with mandatory paired tests; slicing impl from tests violates the workstream's acceptance criteria.

## Related Issues/PRs

- Built on PR #777 (kill-the-deprecation precedent)
- Built on PR #704 (foundation cleanup)
- Tracked in PR #691 (`work/exception-cleanup-inventory.md`)
- Companion to Wave 7 cleanup PRs — operator, agent component, task

## Checklist

- [x] All 6 `:cleanup-needed` spec-parser sites retired
- [x] Single API per site (no deprecate-and-coexist)
- [x] Boundary throw inlined in `parse-spec-file`
- [x] External slingshot caller contracts preserved
- [x] Decomposed test files (six)
- [x] No new throws in anomaly-returning code paths
- [x] `bb pre-commit` green
- [x] Apache 2 license headers preserved
