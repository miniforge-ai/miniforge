<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Reliability Nines — Gap Analysis

**Date:** 2026-03-08
**Spec Version:** 0.6.0-draft
**Overall Completion:** ~5%

This document maps every concept introduced by the 0.6.0 reliability nines amendments
against the current codebase to identify what exists, what's missing, and what order
to build it in.

---

## Gap Matrix

| # | Concept | Spec | Completion | Existing Foundation | Extend | New |
|---|---------|------|:----------:|---------------------|--------|-----|
| 1 | Failure Taxonomy (10-class) | N1 §5.3.3 | 20% | `error_classifier/core.clj` — 3-class (`classify-error`) | agent-runtime | — |
| 2 | SLIs / SLOs / Error Budgets | N1 §5.5 | 0% | `observer/core.clj` — ad-hoc slow-phase detection | observer | **reliability-metrics** |
| 3 | Workflow Tiers | N1 §5.5.1, N2 §9.1 | 0% | — | workflow, dag-executor | — |
| 4 | Degradation Modes | N1 §5.5.5 | 5% | `dag_resilience.clj` — rate-limit pause only | workflow | — |
| 5 | Unified Autonomy (A0-A5) | N1 §5.6 | 0% | — | event-stream, workflow | **autonomy** |
| 6 | Trust Boundaries (TB-1..5) | N1 §5.7 | 15% | `knowledge_safety.clj` — TB-3 ingestion only | policy-pack, gate | — |
| 7 | Evaluation Pipeline | N1 §3.3.3 | 15% | `replay.clj` — state reconstruction, `verify-determinism` | workflow | **evaluation** |
| 8 | Compensation Protocol | N2 §13.6.4 | 0% | — | dag-executor | — |
| 9 | Success Predicates | N2 §13.6.5 | 0% | — | dag-executor | — |
| 10 | Validation Layer Taxonomy | N4 §3.4 | 10% | `gate/policy.clj` — L2 policy layer only | gate, policy-pack | — |
| 11 | Reliability Metric Events | N3 §3.17 | 0% | event-stream schema (no reliability events) | event-stream | — |
| 12 | Repo Intelligence Events | N3 §3.18 | 0% | event-stream schema (no repo-index events) | event-stream | — |
| 13 | Safe-Mode Posture | N8 §3.4 | 10% | `control.clj` — RBAC + `emergency-stop` action | event-stream | — |
| 14 | Tool Operational Semantics | N10 §3.4 | 5% | `tool/core.clj` — comment re future `:tool/timeout-ms` | tool-registry, tool | — |
| 15 | Tool Health Tracking | N10 §3.5 | 15% | `backend_health.clj` — LLM backends only | self-healing | — |
| 16 | Tool Response Validation | N10 §7.4 | 0% | `tool/core.clj` — input validation only | tool | — |
| 17 | Outcome Evidence Extensions | N6 §2.6 | 0% | `evidence_bundle/schema.clj` — no failure-class/tier/SLI | evidence-bundle | — |
| 18 | Golden Set / Eval Artifacts | N6 §3.1.1 | 0% | — | evidence-bundle, artifact | — |
| 19 | Index Quality Metrics | N1 §2.27.9 | 0% | — | — | **index-quality** |
| 20 | Canary Protocol | N1 §2.27.10 | 0% | — | — | **canary** |

**Legend:** *Extend* = existing component needs changes. *New* = new Polylith brick needed (bolded).

---

## Detailed Findings

### 1. Failure Taxonomy (10-class enum)

| | |
|---|---|
| **Spec** | N1 §5.3.3 — 10-value enum: `agent-error`, `task-code`, `tool-error`, `external`, `policy`, `resource`, `timeout`, `concurrency`, `data-integrity`, `unknown` |
| **Exists** | `agent-runtime/error_classifier/core.clj` — `classify-error` returns 3 classes only |
| **Missing** | Expand to 10 classes; wire into all event constructors; add `:failure/class` to `WorkflowFailed`, `AgentFailed`, `EtlFailed`, `PackRunFailed`, `ChainEdgeFailed` schemas |
| **Files** | `components/agent-runtime/src/ai/miniforge/agent_runtime/error_classifier/core.clj` |
| **Tests** | Existing classifier tests cover 3 classes; need 10-class coverage |
| **Completion** | **20%** — classifier exists but taxonomy too narrow |

### 2. SLIs / SLOs / Error Budgets

| | |
|---|---|
| **Spec** | N1 §5.5.2–5.5.4 — 7 mandatory SLIs (success rate, latency p95/p99, gate pass rate, repair success, failure class distribution, event delivery lag), tier-dependent SLO targets, rolling error budgets |
| **Exists** | `observer/core.clj` — `generate-recommendations` does ad-hoc slow-phase detection, not structured SLI computation |
| **Missing** | SLI computation engine, SLO target evaluation, error budget tracking with exhaustion alerts, per-tier per-window calculations |
| **Files** | `components/observer/src/ai/miniforge/observer/core.clj` |
| **Tests** | No SLI/SLO tests |
| **Completion** | **0%** — no structured reliability metrics |

### 3. Workflow Tiers

| | |
|---|---|
| **Spec** | N1 §5.5.1, N2 §9.1 — `:workflow/tier` keyword (`:best-effort` / `:standard` / `:critical`) in workflow spec, defaults to `:standard`, gates SLO targets |
| **Exists** | Nothing — workflow specs have no tier field |
| **Missing** | Add `:workflow/tier` to workflow spec schema, propagate through dag-executor context, use in SLO target lookup |
| **Files** | `components/workflow/src/ai/miniforge/workflow/schema.clj`, `components/dag-executor/src/ai/miniforge/dag_executor/state.clj` |
| **Tests** | No tier-related tests |
| **Completion** | **0%** |

### 4. Degradation Modes

| | |
|---|---|
| **Spec** | N1 §5.5.5 — State machine: `:nominal` → `:degraded` → `:safe-mode`, with defined triggers and recovery |
| **Exists** | `workflow/dag_resilience.clj` — `rate-limit-error?`, `attempt-backend-switch`, `handle-rate-limited-batch` (pauses on rate-limit, a crude form of degradation) |
| **Missing** | Formal state machine, transition triggers (SLO breach, error budget exhaustion), mode-dependent behavior (queue depth limits, autonomy demotion), event emission |
| **Files** | `components/workflow/src/ai/miniforge/workflow/dag_resilience.clj` |
| **Tests** | Rate-limit handling tests exist; no degradation mode tests |
| **Completion** | **5%** — rate-limit pause is a single degradation behavior |

### 5. Unified Autonomy Model (A0-A5)

| | |
|---|---|
| **Spec** | N1 §5.6 — 6 levels (A0 human-in-loop → A5 self-governing), cross-spec mapping to N8 capability levels / N9 automation tiers / N10 trust levels, configurable per workflow |
| **Exists** | Nothing — N8 has `capability-level` (OBSERVE/ADVISE/CONTROL), N9 has automation tiers, N10 has L0-L4 trust, but they're independent |
| **Missing** | Unified A0-A5 enum, mapping functions, per-workflow configuration, autonomy demotion on safe-mode |
| **Files** | None |
| **Tests** | None |
| **Completion** | **0%** — three parallel systems exist but not unified |

### 6. Trust Boundaries (TB-1 through TB-5)

| | |
|---|---|
| **Spec** | N1 §5.7 — TB-1 (user↔control plane), TB-2 (control↔agent), TB-3 (agent↔external), TB-4 (agent↔tool), TB-5 (tool↔target). 5 invariants (TB-INV-1..5), boundary crossing records |
| **Exists** | `policy-pack/knowledge_safety.clj` — prompt injection detection covers TB-3 (ingestion boundary) |
| **Missing** | TB-1, TB-2, TB-4, TB-5 enforcement; boundary crossing record schema; invariant validation |
| **Files** | `components/policy-pack/src/ai/miniforge/policy_pack/knowledge_safety.clj` |
| **Tests** | Knowledge safety tests cover injection detection only |
| **Completion** | **15%** — one of five boundaries partially covered |

### 7. Evaluation Pipeline

| | |
|---|---|
| **Spec** | N1 §3.3.3 — Golden set artifact schema, replay mode (deterministic re-execution), shadow mode (parallel comparison), canary deployment (staged rollout with recall checks) |
| **Exists** | `workflow/replay.clj` — `replay-events`, `verify-determinism` (reconstructs state from event log, does not re-execute workflows) |
| **Missing** | Golden set CRUD, replay as re-execution (not just state reconstruction), shadow mode dual-run, canary deployment with staged rollout |
| **Files** | `components/workflow/src/ai/miniforge/workflow/replay.clj` |
| **Tests** | Replay tests verify event reconstruction and determinism checks |
| **Completion** | **15%** — replay exists but is observation-only, not evaluation |

### 8. Compensation Protocol

| | |
|---|---|
| **Spec** | N2 §13.6.4 — When a node fails after predecessors completed, compensate in reverse dependency order; 3 policies (`:compensate-all`, `:compensate-effects`, `:skip`); compensation functions must be idempotent |
| **Exists** | Nothing — DAG executor has no compensation logic |
| **Missing** | `:cap/compensation-fn` on node capability contract, compensation execution engine, reverse-order traversal, idempotency enforcement |
| **Files** | `components/dag-executor/src/ai/miniforge/dag_executor/state.clj` (lines 93-115, `create-task-state`) |
| **Tests** | None |
| **Completion** | **0%** |

### 9. Success Predicates

| | |
|---|---|
| **Spec** | N2 §13.6.5 — Configurable success criteria per node: `:exit-code`, `:output-contains`, `:schema-valid`, `:metric-threshold`, `:custom-fn`, `:composite`. Schema with `:predicate/type` and `:predicate/config` |
| **Exists** | Nothing — nodes succeed/fail based on exception or result/ok? only |
| **Missing** | `:cap/success-predicate` field, predicate evaluation engine, 6 predicate types |
| **Files** | `components/dag-executor/src/ai/miniforge/dag_executor/state.clj` |
| **Tests** | None |
| **Completion** | **0%** |

### 10. Validation Layer Taxonomy (L0-L4)

| | |
|---|---|
| **Spec** | N4 §3.4 — L0 Syntax (schema) → L1 Semantic (intent) → L2 Policy (rules) → L3 Operational (health/quota) → L4 Authorization (capability). Ordering invariant: lower layers run first |
| **Exists** | `gate/policy.clj` — `evaluate-severity-cascade` (lines 167-188), `check-policy-pack` (lines 230-267) — implements L2 policy layer |
| **Missing** | L0 (schema validation), L1 (semantic intent), L3 (operational health/quota), L4 (authorization); layer ordering enforcement; layer-to-spec mapping |
| **Files** | `components/gate/src/ai/miniforge/gate/policy.clj` |
| **Tests** | Gate tests cover policy evaluation only |
| **Completion** | **10%** — one of five layers implemented |

### 11. Reliability Metric Events

| | |
|---|---|
| **Spec** | N3 §3.17 — 4 event types: `reliability/sli-computed`, `reliability/slo-breach`, `reliability/error-budget-update`, `reliability/degradation-mode-changed` |
| **Exists** | `event-stream/schema.clj` has Malli schemas for all current event types; none of the 4 reliability events exist |
| **Missing** | 4 event schemas, 4 event constructors, emission logic from SLI computation |
| **Files** | `components/event-stream/src/ai/miniforge/event_stream/schema.clj`, `components/event-stream/src/ai/miniforge/event_stream/core.clj` |
| **Tests** | None |
| **Completion** | **0%** |

### 12. Repository Intelligence Events

| | |
|---|---|
| **Spec** | N3 §3.18 — 2 event types: `repo-index/quality-computed`, `repo-index/canary-failed` |
| **Exists** | Nothing |
| **Missing** | 2 event schemas, 2 event constructors, emission from index quality computation |
| **Files** | `components/event-stream/src/ai/miniforge/event_stream/schema.clj` |
| **Tests** | None |
| **Completion** | **0%** |

### 13. Safe-Mode Posture

| | |
|---|---|
| **Spec** | N8 §3.4 — 4 triggers (error budget exhaustion, emergency-stop, cascade detection, external signal), behavior (demote to A0, queue new workflows, pause in-flight), exit protocol (manual + cooldown) |
| **Exists** | `event-stream/control.clj` — `authorize-action` with RBAC, `execute-control-action!`, `default-roles` (admin can `:emergency-stop`) |
| **Missing** | Safe-mode state atom, trigger detection, automatic demotion, queuing behavior, exit protocol with cooldown, safe-mode events |
| **Files** | `components/event-stream/src/ai/miniforge/event_stream/control.clj` |
| **Tests** | Control tests cover RBAC and action dispatch |
| **Completion** | **10%** — emergency-stop action exists but no safe-mode state machine |

### 14. Tool Operational Semantics

| | |
|---|---|
| **Spec** | N10 §3.4 — `:tool/operational` registry extension: timeout, retry (backoff, jitter, retryable classes), circuit-breaker (failure threshold, reset timeout, half-open permits), concurrency (semaphore), fallback tool |
| **Exists** | `tool/core.clj` — comment at line 28 mentions future `:tool/timeout-ms`; `tool-registry/schema.clj` has `ToolConfig` Malli schema with no `:tool/operational` |
| **Missing** | `:tool/operational` schema, retry engine, circuit-breaker state machine, concurrency semaphore, fallback routing |
| **Files** | `components/tool-registry/src/ai/miniforge/tool_registry/schema.clj`, `components/tool/src/ai/miniforge/tool/core.clj` |
| **Tests** | None |
| **Completion** | **5%** — schema placeholder only |

### 15. Tool Health Tracking

| | |
|---|---|
| **Spec** | N10 §3.5 — Per-tool health state: consecutive failures, last success/failure timestamps, circuit-breaker state, availability ratio |
| **Exists** | `self-healing/backend_health.clj` — `record-backend-call!`, `should-switch-backend?`, `trigger-backend-switch!` — tracks LLM backend health only, not general tools |
| **Missing** | Generalize from LLM backends to all tools, per-tool state tracking, circuit-breaker state integration |
| **Files** | `components/self-healing/src/ai/miniforge/self_healing/backend_health.clj` |
| **Tests** | Backend health tests exist for LLM backends |
| **Completion** | **15%** — pattern exists for LLM backends, needs generalization |

### 16. Tool Response Validation

| | |
|---|---|
| **Spec** | N10 §7.4 — Schema validation of tool responses before injection into agent context, injection sanitization, malformed response handling |
| **Exists** | `tool/core.clj` — `FunctionTool` validates inputs only; no output/response validation |
| **Missing** | `:tool/returns` schema field, response validation engine, injection sanitization, malformed response error handling |
| **Files** | `components/tool/src/ai/miniforge/tool/core.clj` |
| **Tests** | None |
| **Completion** | **0%** |

### 17. Outcome Evidence Extensions

| | |
|---|---|
| **Spec** | N6 §2.6 — Outcome evidence extended with `:outcome/failure-class`, `:outcome/tier`, `:outcome/degradation-mode`, `:outcome/sli-measurements` |
| **Exists** | `evidence-bundle/schema.clj` — `outcome-schema` missing all 4 new fields; `collector.clj` — `build-outcome-evidence` (line 215) does not collect them |
| **Missing** | 4 schema fields, collector integration to capture failure class / tier / degradation mode / SLI snapshot |
| **Files** | `components/evidence-bundle/src/ai/miniforge/evidence_bundle/schema.clj`, `components/evidence-bundle/src/ai/miniforge/evidence_bundle/collector.clj` |
| **Tests** | None |
| **Completion** | **0%** |

### 18. Golden Set / Eval Run Artifacts

| | |
|---|---|
| **Spec** | N6 §3.1.1 — `:golden-set` artifact (entries with expected outcomes, pass criteria) and `:eval-run-result` artifact (replay/shadow/canary results with pass/fail per entry) |
| **Exists** | Nothing |
| **Missing** | 2 artifact type schemas, CRUD for golden sets, eval run result generation |
| **Files** | `components/evidence-bundle/`, `components/artifact/` |
| **Tests** | None |
| **Completion** | **0%** |

### 19. Index Quality Metrics

| | |
|---|---|
| **Spec** | N1 §2.27.9 — Schema: repo-id, commit-sha, freshness-lag-ms, coverage-score, symbol-coverage, search-recall, computed-at |
| **Exists** | Nothing — repo-index component exists but has no quality measurement |
| **Missing** | Quality metric computation, freshness lag tracking, coverage scoring, search recall measurement |
| **Files** | None (new component or extend repo-index) |
| **Tests** | None |
| **Completion** | **0%** |

### 20. Canary Protocol

| | |
|---|---|
| **Spec** | N1 §2.27.10 — Golden queries with known-good results, recall threshold (default 0.8), automatic index degradation on canary failure |
| **Exists** | Nothing |
| **Missing** | Golden query set, recall computation, threshold checking, degradation trigger |
| **Files** | None (new component) |
| **Tests** | None |
| **Completion** | **0%** |

---

## Component Impact Map

Which Polylith bricks are touched, and how much work each needs:

| Component | Concepts | Changes |
|-----------|----------|---------|
| **agent-runtime** | 1 | Expand error classifier 3→10 classes |
| **event-stream** | 1, 5, 11, 12, 13 | Failure class on events, reliability events, repo-index events, safe-mode state |
| **workflow** | 3, 4, 7 | Tier field, degradation state machine, replay→re-execution |
| **dag-executor** | 3, 8, 9 | Tier propagation, compensation engine, success predicates |
| **observer** | 2 | SLI computation, SLO evaluation, error budget tracking |
| **gate** | 10 | L0/L1/L3/L4 validation layers, ordering enforcement |
| **policy-pack** | 6, 10 | TB-1/2/4/5 boundaries, layer taxonomy integration |
| **tool** | 14, 16 | Operational semantics execution, response validation |
| **tool-registry** | 14 | `:tool/operational` schema extension |
| **self-healing** | 15 | Generalize backend health → all-tool health |
| **evidence-bundle** | 17, 18 | Outcome extensions, golden-set/eval-run artifact types |
| **artifact** | 18 | Golden set artifact storage |
| **NEW: reliability-metrics** | 2 | SLI/SLO/error budget engine |
| **NEW: autonomy** | 5 | Unified A0-A5 model + mapping |
| **NEW: evaluation** | 7 | Golden set mgmt, shadow mode, canary deployment |
| **NEW: index-quality** | 19 | Repo index quality scoring |
| **NEW: canary** | 20 | Golden query recall checking |

---

## Implementation Tiers (Dependency Order)

### Tier 1 — Foundations (blocks everything)

| # | Concept | Est. Size | Rationale |
|---|---------|-----------|-----------|
| 1 | Failure Taxonomy | S | Extend existing classifier; all SLI/degradation/evidence depends on it |
| 3 | Workflow Tiers | S | Schema field + propagation; SLOs meaningless without tiers |
| 11 | Reliability Metric Events | M | Event schemas + constructors; SLI computation emits these |

### Tier 2 — Core Reliability (uses Tier 1)

| # | Concept | Est. Size | Rationale |
|---|---------|-----------|-----------|
| 2 | SLIs / SLOs / Error Budgets | L | New component; computes from events, evaluates against tier targets |
| 4 | Degradation Modes | M | State machine using SLO breach / error budget signals |
| 17 | Outcome Evidence Extensions | S | Schema + collector changes; needs failure class + tier |

### Tier 3 — Governance (uses Tier 1-2)

| # | Concept | Est. Size | Rationale |
|---|---------|-----------|-----------|
| 5 | Unified Autonomy Model | M | New component; maps A0-A5 across N8/N9/N10 systems |
| 6 | Trust Boundaries | M | Extend policy-pack; 4 remaining boundaries |
| 10 | Validation Layer Taxonomy | M | Extend gate; 4 missing layers + ordering |
| 13 | Safe-Mode Posture | M | Extend control.clj; needs degradation mode + autonomy |
| 14 | Tool Operational Semantics | L | Schema + retry/circuit-breaker/fallback engine |

### Tier 4 — Execution Contracts (uses Tier 1-3)

| # | Concept | Est. Size | Rationale |
|---|---------|-----------|-----------|
| 8 | Compensation Protocol | M | DAG executor extension; reverse-order compensate |
| 9 | Success Predicates | M | DAG executor extension; 6 predicate types |
| 15 | Tool Health Tracking | S | Generalize backend_health.clj pattern |
| 16 | Tool Response Validation | M | Output schema checking + sanitization |

### Tier 5 — Evaluation & Intelligence (uses Tier 1-4)

| # | Concept | Est. Size | Rationale |
|---|---------|-----------|-----------|
| 7 | Evaluation Pipeline | L | Extend replay into re-execution, add shadow + canary |
| 12 | Repo Intelligence Events | S | 2 event schemas + emission |
| 18 | Golden Set / Eval Artifacts | M | Artifact types + CRUD |
| 19 | Index Quality Metrics | M | New component; freshness, coverage, recall scoring |
| 20 | Canary Protocol | M | New component; golden queries + recall threshold |

**Size key:** S = 1-2 days, M = 3-5 days, L = 1-2 weeks

---

## Summary

| Tier | Concepts | Total Items | Est. Effort |
|------|----------|:-----------:|-------------|
| 1 — Foundations | 1, 3, 11 | 3 | ~1 week |
| 2 — Core Reliability | 2, 4, 17 | 3 | ~2 weeks |
| 3 — Governance | 5, 6, 10, 13, 14 | 5 | ~4 weeks |
| 4 — Execution Contracts | 8, 9, 15, 16 | 4 | ~2 weeks |
| 5 — Eval & Intelligence | 7, 12, 18, 19, 20 | 5 | ~3 weeks |
| **Total** | | **20** | **~12 weeks** |

New Polylith bricks needed: **5** (reliability-metrics, autonomy, evaluation, index-quality, canary)

Existing bricks modified: **12** (agent-runtime, event-stream, workflow, dag-executor, observer,
gate, policy-pack, tool, tool-registry, self-healing, evidence-bundle, artifact)
