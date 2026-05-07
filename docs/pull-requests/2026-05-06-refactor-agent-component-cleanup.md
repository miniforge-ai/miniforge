# refactor: exceptions-as-data cleanup of agent component

## Overview

Migrates 6 throw sites across `components/agent/` (`prompts.clj`, `planner.clj`, `meta_protocol.clj`, `file_artifacts.clj`) to a single anomaly-returning API per site, with boundary throws inlined where external slingshot callers depend on the legacy taxonomy.

Mirrors the kill-the-deprecation pattern from PR #777.

## Motivation

Per `work/exception-cleanup-inventory.md`, the `agent` component had 6 `:cleanup-needed` / `:ambiguous` sites distributed across four files. The `prompts.clj` sites were tagged `:ambiguous` (missing prompt resources currently fatal at startup); the inventory left the design pivot ("construct with fallback prompts") for a separate workstream and asked this PR to migrate the throw shape only.

## Base Branch

`main`

## Depends On

- `ai.miniforge.anomaly` (merged)

## Layer

Refactor / per-component cleanup tier.

## What This Adds / Changes

`components/agent/src/.../file_artifacts.clj`:

- `snapshot-working-dir` git failure now returns a `:fault` anomaly. The single in-component caller (`collect-written-files`) branches on `anomaly?` and returns nil — no boundary escalation needed.

`components/agent/src/.../meta_protocol.clj`:

- `create-meta-config` missing `:id` / `:name` returns `:invalid-input` anomaly. The only caller passes valid input today; no boundary escalation.

`components/agent/src/.../planner.clj`:

- Two new anomaly-returning private helpers — `parsed-plan-or-anomaly` and `require-llm-client-or-anomaly`. Boundary throws live at the call sites where `planner_test.clj` (and external slingshot callers) assert the legacy `:anomalies.agent/invoke-failed` and `:anomalies.agent/llm-error` taxonomy.
- The previously redundant `(if llm-client …)` wrapper was removed — `require-llm-client-or-anomaly` already escalates above it, so the else-branch was unreachable.

`components/agent/src/.../prompts.clj` (3 sites):

- Missing prompt resource at startup remains a fail-fast boundary throw, but now uses the canonical `:anomalies/fault` slingshot category instead of the bespoke `:anomalies/not-found` ex-info. Design pivot ("construct with fallback prompts") deferred per the brief.

`components/agent/test/.../file_artifacts_extended_test.clj`:

- One existing thrown-anomaly assertion migrated to the anomaly-shape return.

`components/agent/test/.../anomaly/` (new):

- `snapshot_working_dir_test.clj`
- `create_meta_config_test.clj`
- `planner_helpers_test.clj`

Each covers happy path + failure path + boundary-escalation behaviour.

## Per-site classification

| File | Site | Anomaly type | Boundary? |
|------|------|--------------|-----------|
| `file_artifacts.clj:164` | `snapshot-working-dir` git failure | `:fault` | No |
| `meta_protocol.clj:133` | `create-meta-config` missing `:id`/`:name` | `:invalid-input` | No |
| `planner.clj:467` | EDN parse miss | `:fault` (helper) → boundary throw `:anomalies.agent/invoke-failed` | Yes |
| `planner.clj:518` | Missing LLM backend | `:invalid-input` (helper) → boundary throw `:anomalies.agent/llm-error` | Yes |
| `prompts.clj:47, 52, 70` | Missing prompt resource | `:anomalies/fault` slingshot category | Yes — fail-fast startup |

## Strata Affected

- `ai.miniforge.agent.file-artifacts`
- `ai.miniforge.agent.meta-protocol`
- `ai.miniforge.agent.planner`
- `ai.miniforge.agent.prompts`
- `ai.miniforge.agent.file-artifacts-extended-test` (one assertion migrated)
- New `ai.miniforge.agent.anomaly.*` test namespaces

## Testing Plan

`bb pre-commit` green: **5108 tests / 23274 assertions / 0 failures / 0 errors**. GraalVM compat: 6 tests, 490 assertions, 0 failures.

One transient flake in `workflow.merge-resolution-test/conflict-fixture` (unrelated to agent component); passed on retry.

Required `MINIFORGE_COMMIT_BUDGET_OVERRIDE` since the diff is +408/-57 across 4 source files plus per-site test coverage.

## Deployment Plan

No migration. External slingshot callers continue to see `:anomalies.agent/invoke-failed` and `:anomalies.agent/llm-error` ex-info shapes via the boundary throws in `planner.clj`. The `prompts.clj` startup fail-fast boundary now uses the canonical `:anomalies/fault` category — only an internal change in classification keyword; the throw still happens at the same code path.

## Notes / Surprises

- **`planner.clj` `(if llm-client …)` removed.** The `require-llm-client-or-anomaly` boundary throw above already escalates, so the else-branch was unreachable.
- **`:anomaly/category` (slingshot) and `:anomaly/type` (data) namespaces remain distinct.** Helpers return canonical `:not-found` / `:invalid-input` / `:fault`; boundary throws use the legacy `:anomalies.agent/*` taxonomy that external `try+` callers depend on. Same shape as `loader.clj` post-#787.
- **Prompts design pivot deferred.** "Construct with fallback prompts and only fail at first invocation" is a future workstream; this PR moves the throw category only.

## Related Issues/PRs

- Built on PR #777 (kill-the-deprecation precedent)
- Tracked in PR #691 (`work/exception-cleanup-inventory.md`)
- Companion to Wave 7 cleanup PRs — operator, spec-parser, task

## Checklist

- [x] All 6 agent-component sites retired
- [x] Single API per site
- [x] Boundary throws inlined where external slingshot callers depend
- [x] External caller contracts preserved
- [x] Decomposed test files
- [x] No new throws in anomaly-returning code paths
- [x] `bb pre-commit` green
