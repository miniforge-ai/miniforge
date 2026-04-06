<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# I-ANOMALY-SYSTEM — Canonical Error Representation

**Status:** Informative
**Date:** 2026-02-07
**Version:** 1.0.0
**Related specs:** N1-architecture.md, N2-workflows.md, N3-event-stream.md, N6-evidence-provenance.md

---

## 1. Purpose

Establish a single internal error representation — canonical anomaly maps — that flows
through the entire miniforge system as plain data. Translation to external formats
(HTTP, events, user messages, logs, evidence) happens only at system boundaries.

Prior to this work, six competing error shapes existed across the codebase with no
systematic mapping to any external format.

---

## 2. Problem: Six Competing Error Shapes

| Shape | Pattern | Primary Consumers |
|-------|---------|-------------------|
| 1. Builder `:status :error` | `{:status :error :error {:message s :data m}}` | `response/builder`, specialized agents |
| 2. Builder `:success false` | `{:success false :error {:message s}}` | `response/builder/failure` |
| 3. Schema `:success? false` | `{:success? false :error s}` | schema, policy-pack, tool-registry, observer, loop/outer, workflow |
| 4. Tool/LLM `:success false` | `{:success false :error {:type "cli_error" :message s}}` | tool/core, llm_client, cli/web/github, repo-dag |
| 5. Gate `:gate/passed? false` | `{:gate/errors [{:code kw :message s :location m}]}` | loop/gates, gate protocol impls |
| 6. Ad-hoc loop errors | `{:code :generation-error :message s}` | loop/inner catch blocks, loop/repair |

Additionally, `workflow/execution.clj` carries `:execution/errors` as
`[{:type :phase-error :phase kw :message s}]`.

No systematic mapping existed from any shape to HTTP responses, events, user messages,
log entries, or evidence bundle outcomes.

---

## 3. Destination: Canonical Anomaly Maps

Every error becomes a plain data map with one required key:

```clojure
;; REQUIRED
{:anomaly/category   keyword          ; From taxonomy (e.g. :anomalies.gate/validation-failed)
 :anomaly/message    string}          ; Programmer-facing diagnostic

;; STANDARD OPTIONAL
{:anomaly/id         uuid             ; Unique instance ID for tracing
 :anomaly/timestamp  inst             ; When it occurred
 :anomaly/phase      keyword          ; SDLC phase (:plan, :implement, :verify, etc.)
 :anomaly/operation  keyword}         ; Operation that failed

;; DOMAIN CONTEXT (namespaced, open)
{:anomaly.gate/errors    [GateError]  ; Preserved gate errors with :code :message :location
 :anomaly.agent/role     keyword
 :anomaly.agent/task-id  uuid
 :anomaly.llm/model      string
 :anomaly.repair/strategy keyword
 :anomaly.repair/attempts int}

;; EXCEPTION PROVENANCE (only from catch boundaries)
{:anomaly/ex-message string
 :anomaly/ex-data    map
 :anomaly/ex-class   string}
```

Design decisions:

- `anomaly-map?` checks for `:anomaly/category` — that discriminates anomaly maps from other maps
- Categories reuse the existing keyword taxonomy verbatim
- Gate `:location` data is preserved under `:anomaly.gate/errors`
- Domain context uses namespaced keys — never conflicts
- Anomaly maps are return values, not exceptions
- Translation to HTTP/events/messages/logs happens only at boundaries

---

## 4. Anomaly Taxonomy

Five category groups, all under the `:anomalies` namespace:

### 4.1 General (Cognitect-compatible)

| Category | Retryable? | Meaning |
|----------|-----------|---------|
| `:anomalies/unavailable` | yes | Temporarily unavailable |
| `:anomalies/interrupted` | yes | Operation interrupted |
| `:anomalies/incorrect` | no | Bad input or configuration |
| `:anomalies/forbidden` | no | Not authorized |
| `:anomalies/not-found` | no | Resource not found |
| `:anomalies/conflict` | no | Conflicting state |
| `:anomalies/fault` | no | Internal error |
| `:anomalies/unsupported` | no | Operation not supported |
| `:anomalies/busy` | yes | Rate limited / overloaded |
| `:anomalies/timeout` | yes | Operation timed out |

### 4.2 Phase Execution

`:anomalies.phase/unknown-phase`, `enter-failed`, `leave-failed`,
`budget-exceeded`, `no-agent`, `agent-failed`

### 4.3 Gate Validation

`:anomalies.gate/unknown-gate`, `check-failed`, `validation-failed`,
`repair-failed`, `no-repair`

### 4.4 Agent

`:anomalies.agent/unknown-agent`, `invoke-failed`, `parse-failed`, `llm-error`

### 4.5 Workflow Orchestration

`:anomalies.workflow/empty-pipeline`, `invalid-config`, `max-phases`,
`invalid-transition`, `rollback-limit`

---

## 5. Component Architecture

```text
components/response/src/ai/miniforge/response/
├── anomaly.clj      ; Taxonomy + anomaly map constructors
├── translate.clj    ; All boundary translators (6 functions)
├── chain.clj        ; Response chain (enhanced for anomaly maps)
├── builder.clj      ; Legacy success/error builders
└── interface.clj    ; Public API re-exports
```

### 5.1 Constructors (anomaly.clj)

```clojure
(make-anomaly :anomalies/fault "Something broke")
(make-anomaly :anomalies/fault "Something broke" {:anomaly/phase :verify})
(from-exception ex)
(from-exception ex classifier-fn)
(gate-anomaly :anomalies.gate/validation-failed "Gate failed" gate-errors)
(agent-anomaly :anomalies.agent/invoke-failed "Timeout" :implementer)
```

### 5.2 Predicates

```clojure
(anomaly-map? x)        ; Has :anomaly/category?
(anomaly? kw)           ; Known taxonomy keyword?
(retryable? kw)         ; Will retry help?
(anomaly-category kw)   ; -> :general, :phase, :gate, :agent, :workflow
```

### 5.3 Boundary Translators (translate.clj)

| Function | From | To | Boundary |
|----------|------|----|----------|
| `anomaly->http-response` | anomaly map | Ring response | HTTP edge |
| `anomaly->user-message` | anomaly map | human string | TUI / escalation |
| `anomaly->log-data` | anomaly map | structured map | logging |
| `anomaly->event-data` | anomaly map | event fields | event stream |
| `anomaly->outcome-evidence` | anomaly map | outcome fields | evidence bundle |
| `coerce` | any error shape | anomaly map | inbound bridge |

### 5.4 Response Chain Enhancement (chain.clj)

`wrap-response` accepts keyword OR anomaly map for the `anomaly` parameter.
When a map is passed, both `:anomaly` (keyword, backward compat) and
`:anomaly-map` (full map) are stored. The `errors` function surfaces
`:anomaly-map` when available.

---

## 6. Migration Pattern

All error producers now include an `:anomaly` key alongside existing keys.
Old consumers continue reading `:success`, `:success?`, `:code`, `:message`.
New consumers read `:anomaly`.

### 6.1 Producers (Phase 3 — complete)

| Component | File | Change |
|-----------|------|--------|
| Gates | `loop/gates.clj` | `fail-result` adds `:gate/anomaly` |
| Agent executor | `agent/core.clj` | Catch block adds `:anomaly (from-exception e)` |
| Inner loop | `loop/inner.clj` | Error maps include `:anomaly` |
| Tools | `tool/core.clj` | Execute catch + not-found add `:anomaly` |
| LLM client | `llm/llm_client.clj` | `error-response` adds `:anomaly` |
| Schema helpers | `schema/interface.clj` | `failure`, `exception-failure` add `:anomaly` |
| Workflow | `workflow/execution.clj` | Error entries carry anomaly maps |

### 6.2 Boundary Consumers (Phase 4 — complete)

| Boundary | File | Change |
|----------|------|--------|
| HTTP | `cli/web/response.clj` | `from-anomaly` using `anomaly->http-response` |
| HTTP | `cli/web/handlers.clj` | `summary` detects `:anomaly` in results |
| Events | `event-stream/core.clj` | `workflow-failed` accepts anomaly maps |
| Evidence | `evidence-bundle/collector.clj` | Uses `anomaly->outcome-evidence` |
| Escalation | `loop/escalation.clj` | Reads `:anomaly/message` and `:anomaly/category` |

---

## 7. HTTP Status Mapping

| Anomaly Category | HTTP Status |
|-----------------|-------------|
| `:anomalies/incorrect` | 400 |
| `:anomalies/forbidden` | 403 |
| `:anomalies/not-found` | 404 |
| `:anomalies/conflict` | 409 |
| `:anomalies/busy` | 429 |
| `:anomalies/fault` | 500 |
| `:anomalies/unsupported` | 501 |
| `:anomalies/unavailable`, `/interrupted` | 503 |
| `:anomalies/timeout` | 504 |
| `:anomalies.gate/*` | 422 |
| `:anomalies.agent/llm-error`, `/invoke-failed` | 502 |
| `:anomalies.phase/budget-exceeded` | 429 |
| `:anomalies.workflow/empty-pipeline`, `/invalid-config` | 400 |

---

## 8. Future Cleanup

The following legacy patterns remain in the codebase and can be migrated
incrementally as components are modified:

- ~60 sites returning `{:success? false ...}` across schema, tool-registry,
  workflow, policy-pack, release-executor, and loop components
- ~26 sites returning `{:success false ...}` across tool, agent, LLM,
  evidence, and CLI components
- `builder/failure` function (produces `:success false` shape) — can be
  replaced with `make-anomaly` at call sites

The `coerce` function handles all known shapes and can be used at any
boundary where legacy error shapes might still appear.
