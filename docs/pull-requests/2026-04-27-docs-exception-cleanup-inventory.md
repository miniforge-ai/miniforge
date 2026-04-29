# docs: inventory exception/throw sites for cleanup

## Overview

Adds `work/exception-cleanup-inventory.md` — a categorized, file/line-resolved inventory of every `throw`, `ex-info`,
and exception-class instantiation across miniforge `components/` and `bases/`. The inventory partitions hits into
`:boundary`, `:fatal-only`, `:cleanup-needed`, and `:ambiguous`, and recommends PR-sizing per component.

## Motivation

Miniforge has regressed on its data-first error-handling discipline because agent-written code defaults to throwing
exceptions. Before scheduling and parallelizing the cleanup, we need an honest list of every throw site categorized by
whether it is at an acceptable boundary or needs to be replaced with anomaly-returning code.

This inventory is the input to the broader exceptions-as-data cleanup workstream and pairs with the new foundations rule
`005 exceptions-as-data` landing in `miniforge-standards`.

## Base Branch

`main`

## Depends On

None. Pure documentation; no source changes.

## Layer

Documentation / planning artifact under `work/`. No source code touched.

## What This Adds

- `work/exception-cleanup-inventory.md` (891 lines)
  - Method note (ripgrep over `components/` and `bases/`, manual categorization)
  - Summary table (boundary / fatal-only / cleanup-needed / ambiguous)
  - Per-component breakdown with file:line citations
  - PR-sizing recommendations per component
  - Recommended cleanup ordering (foundations → high-density components → connector family → workflow surface →
    mid-density)
  - Ambiguous cases needing human review (5 highlighted examples)

## Total counts (production source)

| Category | Count |
|----------|------:|
| `:boundary` | 41 |
| `:fatal-only` | 96 |
| `:cleanup-needed` | **158** |
| `:ambiguous` | 37 |
| **Total production hits** | **~360** |

Plus ~493 test-file hits (predominantly `(is (thrown? ...))` matchers — counted, but treated as derivative work that
will be updated alongside the related production PR rather than scheduled separately).

## Components with the highest cleanup load

1. `repo-dag` — 12 cleanup-needed in `core.clj` alone. Mostly DAG-not-found and validation lookups; mechanical to
  convert.
2. `workflow` — 9 cleanup-needed across 5 namespaces. Only component recommended for a **2-PR split** (runtime vs.
  loader/registry).
3. `spec-parser` — 6 in `core.clj` (parsers + workflow validation).
4. `agent` — 6 distributed across `prompts.clj`, `planner.clj`, `meta_protocol.clj`, `file_artifacts.clj`.
5. `operator` — 5 of 6 hits are cleanup-needed (intervention-validation cascade in one function).
6. `task` — 5 cleanup-needed (FSM transition + lookup pattern repeated).
7. `connector-{jira, gitlab, github, excel}` — ~5 each, identical patterns. Recommend extracting `require-handle!` /
  `validate-auth!` helpers to `connector` core first, then a single PR or per-connector PRs.

## Recommended ordering

Full reasoning lives in the inventory. In short:

1. **Foundation:** `schema/validate`, `dag-primitives/unwrap`, plus all connector `validate!` schema helpers — high
  leverage, every downstream gets simpler.
2. **High-density:** `repo-dag`, `spec-parser`, `operator`, `task`.
3. **Connector family:** one PR or per-connector.
4. **Workflow surface:** split into 2 PRs.
5. **Mid-density:** `pr-lifecycle/controller`, `event-stream/listeners`, `tool-registry`, `policy-pack`, `gate*`,
  `agent`.
6. **CLI base:** `etl`, `workflow_runner` argument validation.

## Notable observations

- **`response/anomaly` already provides `throw-anomaly!`** as the canonical primitive (slingshot `throw+` of an anomaly
  map). About a third of existing throw sites already use it — the cleanup is largely about moving from `throw-anomaly!`
  to *return* anomaly inside non-boundary code paths, not about introducing a new primitive.
- **Test coupling is asymmetric** in one place: `tui-views/persistence/github_*` tests carry ~52 throw-related hits but
  `tui-views/src/` only has 1 throw site. Those tests appear to exercise a `connector-github` / `pr-sync` boundary;
  flagged in the inventory to verify during cleanup that assertions match where the throws originate.

## Most interesting `:ambiguous` cases (5 examples)

Surfaced for human review before mechanical cleanup:

1. **`workflow/runner.clj:156`** — `:anomalies.dashboard/stop` thrown anomaly caught at `runner.clj:387` to implement
  *cooperative cancellation*. Probably intentional control-flow-via-exception; worth confirming vs. a poll-and-flag
  pattern.
2. **`connector-http/request.clj:75-79`** — `throw-on-failure!` as a public helper used by ~20 sites to short-circuit
  pagination. Decision affects whether we keep it as a sanctioned boundary primitive or rewrite all callers.
3. **`dag-executor/protocols/impl/docker.clj:489-495`** — `:throw-on-error?` flag exposing both behaviours; single
  caller passes `true`. Should we just delete the throwing branch?
4. **`agent/prompts.clj:47, 52, 70`** — Missing prompt resources currently fatal at startup. Could allow agents to
  construct with fallback prompts and fail at first invocation instead — design call rather than refactor.
5. **`phase-software-factory/{implement,verify}.clj`** — Phase pre-flight throws (no worktree / no environment). Phases
  already have a `response/failure` shape; could be returned as failed-phase results so the orchestrator records them in
  `execution/errors` like every other phase failure.

## Strata Affected

None — documentation only. No source changes.

## Testing Plan

- `bb pre-commit` — pre-commit hook caught one Markdown lint issue (`**Sub-grouping**` styled as a heading violating
  MD036), fixed in the same commit.
- `test:graalvm` — 462 tests pass.
- No source code touched, so no functional tests added.

## Deployment Plan

No deployment. Documentation only. Inventory is the input to subsequent cleanup PRs.

## Related Issues/PRs

- Companion standards rule `feat/exceptions-as-data-rule` lands in `miniforge-standards` (dewey 005).
- The cleanup workstream will be sequenced from this inventory's recommended ordering.

## Checklist

- [x] Every `throw`/`ex-info` site cited with file:line
- [x] Categorized into `:boundary` / `:fatal-only` / `:cleanup-needed` / `:ambiguous`
- [x] PR-sizing recommendation per component
- [x] Cleanup ordering recommended
- [x] Ambiguous cases surfaced for human review
- [x] `bb pre-commit` green
