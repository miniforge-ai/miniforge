<!--
  Title: Miniforge.ai
  Subtitle: Anomaly convergence runbook (response → ai.miniforge.anomaly)
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under the Apache License, Version 2.0.
-->

# Anomaly convergence runbook

Retire the legacy `ai.miniforge.response` anomaly representation
(`:anomaly/category`, a ~60-keyword taxonomy) in favour of the canonical
`ai.miniforge.anomaly` component (`:anomaly/type` + optional
`:anomaly/subtype`). `response` keeps its non-anomaly pipeline-result
responsibilities; only its anomaly *shape* is retired.

The migration is **data-first throughout** (no `try+`/`catch` on
categories), so it is a mechanical shape + taxonomy change, not a
control-flow rewrite — but anomalies cross brick boundaries, so producers
and the routing consumers (`failure-classifier`, `response/translate`)
must agree on the shape.

## Design decisions

- **Two axes.** Generic `:anomaly/type` stays the cognitect-style
  vocabulary (now + `:fatal` + `:exhausted`). The original domain
  category is preserved **verbatim** as `:anomaly/subtype` — so routing
  code moves from reading `:anomaly/category` to `:anomaly/subtype` with
  identical keyword values. "gate", "agent", etc. are failure *sources*,
  not failure *semantics*, so they never enter the type vocabulary.
- **`:exhausted` added** to the type vocabulary: a budget/limit/effort
  ceiling was reached (loop/retry/phase/token budgets). The one genuine
  gap in the cognitect set for this platform.
- Generic helpers `validation-anomaly` / `exception-anomaly` live in the
  canonical component (#995); career-specific `redact-artifact-row` /
  `slim-failure-message` stay as a career-anomaly extension.

## Waves

- **W0** (#995, merged) — canonical superset helpers.
- **W1** (foundational) — `:anomaly/subtype` convention + `:exhausted`
  type (#998); then migrate `failure-classifier` (`rules.edn` dispatch)
  and `response/translate` (`anomaly→http-status / user-message /
  log-data / event-data`) to dispatch on `subtype` (fall back to `type`).
- **W2** — flip miniforge producers (~96 files) to canonical, applying
  the map below. One coordinated branch; full suite is the cross-brick
  consistency gate.
- **W3** — rewire the career `career-anomaly` facade + migrate career's
  ~132 files (`:anomaly/category` reads + construction).
- **W4** — adopt `let-ok` across canonical sites (folds in career#243).
- **W5** — delete the legacy response anomaly surface + dead code.

## Category → (type, subtype) map

`subtype = the legacy category keyword verbatim` for every domain/custom
entry. The eight cognitect-standard categories map 1:1 to a type with **no
subtype**.

### Generic standard (no subtype)

| legacy category | `:anomaly/type` |
| --- | --- |
| `:anomalies/incorrect` | `:invalid-input` |
| `:anomalies/not-found` | `:not-found` |
| `:anomalies/fault` | `:fault` |
| `:anomalies/unavailable` | `:unavailable` |
| `:anomalies/unsupported` | `:unsupported` |
| `:anomalies/timeout` | `:timeout` |
| `:anomalies/forbidden` | `:unauthorized` |
| `:anomalies/conflict` | `:conflict` |

### Generic custom (subtype = keyword)

| legacy category | `:anomaly/type` |
| --- | --- |
| `:anomalies/busy` | `:unavailable` |
| `:anomalies/interrupted` | `:fault` |
| `:anomalies/dag-non-forest` | `:conflict` |
| `:anomalies/dag-multi-parent-conflict` | `:conflict` |
| `:anomalies/dag-multi-parent-merge-failed` | `:conflict` |
| `:anomalies/dag-multi-parent-unresolvable` | `:conflict` |
| `:anomalies/dag-multi-parent-branch-unresolvable` | `:conflict` |
| `:anomalies/dag-multi-parent-unrelated-histories` | `:conflict` |
| `:anomalies/dag-multi-parent-strategy-unsupported` | `:unsupported` |

### Domain (subtype = keyword)

| legacy category | `:anomaly/type` |
| --- | --- |
| `:anomalies.agent/tool-loop` | `:exhausted` |
| `:anomalies.agent/runaway` | `:exhausted` |
| `:anomalies.agent/repeated-failure` | `:exhausted` |
| `:anomalies.agent/llm-error` | `:fault` |
| `:anomalies.agent/invoke-failed` | `:fault` |
| `:anomalies.agent/parse-failed` | `:fault` |
| `:anomalies.agent/validation-failed` | `:invalid-input` |
| `:anomalies.agent/unknown-agent` | `:not-found` |
| `:anomalies.agent/rate-limited` | `:unavailable` |
| `:anomalies.gate/validation-failed` | `:invalid-input` |
| `:anomalies.gate/check-failed` | `:invalid-input` |
| `:anomalies.gate/repair-failed` | `:fault` |
| `:anomalies.gate/no-repair` | `:fault` |
| `:anomalies.gate/unknown-gate` | `:not-found` |
| `:anomalies.workflow/invalid-config` | `:invalid-input` |
| `:anomalies.workflow/invalid-supervisor` | `:invalid-input` |
| `:anomalies.workflow/empty-pipeline` | `:invalid-input` |
| `:anomalies.workflow/invalid-transition` | `:conflict` |
| `:anomalies.workflow/resume-non-terminal` | `:conflict` |
| `:anomalies.workflow/no-capsule-executor` | `:not-found` |
| `:anomalies.workflow/max-phases` | `:exhausted` |
| `:anomalies.workflow/rollback-limit` | `:exhausted` |
| `:anomalies.workflow/max-redirects-exceeded` | `:exhausted` |
| `:anomalies.workflow/halted-by-supervision` | `:fault` |
| `:anomalies.phase/budget-exceeded` | `:exhausted` |
| `:anomalies.phase/agent-failed` | `:fault` |
| `:anomalies.phase/enter-failed` | `:fault` |
| `:anomalies.phase/leave-failed` | `:fault` |
| `:anomalies.phase/no-agent` | `:not-found` |
| `:anomalies.phase/unknown-phase` | `:not-found` |
| `:anomalies.llm/rate-limited` | `:unavailable` |
| `:anomalies.llm/unavailable` | `:unavailable` |
| `:anomalies.llm/timeout` | `:timeout` |
| `:anomalies.llm/context-exceeded` | `:exhausted` |
| `:anomalies.review/stagnation` | `:exhausted` |
| `:anomalies.review/fuzzy-stagnation` | `:exhausted` |
| `:anomalies.executor/unavailable` | `:unavailable` |
| `:anomalies.executor/timeout` | `:timeout` |
| `:anomalies.executor/acquisition-failed` | `:unavailable` |
| `:anomalies.budget/exceeded` | `:exhausted` |
| `:anomalies.dashboard/stop` | `:fault` |
| `:anomalies.custom/something` | `:fault` |

### Cleanup (not real categories)

`:anomalies/fault.`, `:anomalies/not-found.`,
`:anomalies/dag-multi-parent-strategy-unsupported.` — trailing-dot
artifacts in prose/comments, not keywords. Fix or ignore; do not map.
