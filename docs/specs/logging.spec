# miniforge.ai — Logging Specification

**Version:** 0.1.0  
**Status:** Draft  
**Date:** 2026-01-18  

---

## 1. Overview

### 1.1 Purpose

miniforge.ai uses structured EDN logging as the **primary data substrate** for:

- Debugging and troubleshooting
- Traceability and provenance
- Audit trails and compliance
- Performance analysis
- Meta loop signal collection
- Scenario-based testing in production

Logs are not an afterthought—they are first-class artifacts that power the system's self-awareness.

### 1.2 Design Principles

1. **Data, not strings**: All log entries are structured EDN maps, not formatted text
2. **Append-only**: Logs are immutable; corrections are new entries referencing old
3. **Queryable**: Log storage supports efficient filtering and aggregation
4. **Scenario-tagged**: Every entry carries context for test correlation
5. **Cost-aware**: Verbosity is configurable; meta loop can tune logging levels

---

## 2. Log Entry Schema

### 2.1 Core Schema

```clojure
{:log/id           uuid              ; unique entry identifier
 :log/timestamp    inst              ; wall-clock time (ISO-8601)
 :log/level        keyword           ; :trace, :debug, :info, :warn, :error, :fatal
 :log/category     keyword           ; :agent, :loop, :policy, :artifact, :system
 :log/event        keyword           ; specific event type (see Event Taxonomy)
 :log/message      string            ; human-readable summary (optional)
 
 ;; Context (always present)
 :ctx/workflow-id  uuid              ; outer loop instance
 :ctx/task-id      uuid              ; current task (if applicable)
 :ctx/agent-id     uuid              ; acting agent (if applicable)
 :ctx/phase        keyword           ; :plan, :design, :implement, :verify, :review, :release, :observe
 :ctx/loop         keyword           ; :inner, :outer, :meta
 
 ;; Scenario tracking (for testing)
 :scenario/id      uuid              ; test scenario identifier (nil in production)
 :scenario/tags    #{keyword}        ; [:happy-path, :error-handling, :rollback, ...]
 
 ;; Payload (event-specific data)
 :data             map               ; structured event data
 
 ;; Correlation
 :trace/id         uuid              ; distributed trace ID
 :span/id          uuid              ; span within trace
 :parent-span/id   uuid              ; parent span (if nested)
 
 ;; Performance
 :perf/duration-ms long              ; operation duration (if timed)
 :perf/tokens-used long              ; LLM tokens consumed (if applicable)
 :perf/cost-usd    double}           ; estimated cost (if applicable)
```

### 2.2 Event Taxonomy

#### Agent Events

| Event                      | Level | Data Keys                                      |
|----------------------------|-------|------------------------------------------------|
| `:agent/task-started`      | info  | `:agent-role`, `:task-type`, `:inputs`         |
| `:agent/task-completed`    | info  | `:outputs`, `:duration-ms`, `:tokens-used`     |
| `:agent/task-failed`       | error | `:error`, `:retry-count`, `:will-retry?`       |
| `:agent/prompt-sent`       | debug | `:prompt-hash`, `:model`, `:token-count`       |
| `:agent/response-received` | debug | `:response-hash`, `:token-count`, `:latency-ms`|
| `:agent/memory-updated`    | trace | `:memory-key`, `:old-hash`, `:new-hash`        |

#### Loop Events

| Event                       | Level | Data Keys                                      |
|-----------------------------|-------|------------------------------------------------|
| `:inner/iteration-started`  | debug | `:iteration`, `:artifact-type`                 |
| `:inner/validation-passed`  | info  | `:gates-checked`, `:duration-ms`               |
| `:inner/validation-failed`  | warn  | `:gate`, `:failure-reason`, `:will-repair?`    |
| `:inner/repair-attempted`   | debug | `:repair-strategy`, `:feedback`                |
| `:inner/escalated`          | warn  | `:reason`, `:iterations-exhausted`             |
| `:outer/phase-entered`      | info  | `:phase`, `:inputs`                            |
| `:outer/phase-completed`    | info  | `:phase`, `:outputs`, `:duration-ms`           |
| `:outer/phase-failed`       | error | `:phase`, `:error`, `:rollback-to`             |
| `:outer/workflow-completed` | info  | `:total-duration-ms`, `:total-cost-usd`        |
| `:meta/signal-collected`    | debug | `:signal-type`, `:value`, `:source`            |
| `:meta/improvement-proposed`| info  | `:improvement-type`, `:rationale`              |
| `:meta/improvement-applied` | info  | `:improvement-id`, `:rollout-strategy`         |

#### Policy Events

| Event                    | Level | Data Keys                                        |
|--------------------------|-------|--------------------------------------------------|
| `:policy/gate-evaluated` | debug | `:gate`, `:result`, `:artifact-id`               |
| `:policy/budget-checked` | debug | `:budget-type`, `:used`, `:limit`, `:remaining`  |
| `:policy/budget-exceeded`| warn  | `:budget-type`, `:used`, `:limit`, `:action`     |
| `:policy/escalation`     | info  | `:reason`, `:escalation-target`                  |
| `:policy/human-required` | info  | `:approval-type`, `:artifact-id`, `:timeout`     |
| `:policy/human-approved` | info  | `:approval-type`, `:approver`, `:decision`       |

#### Artifact Events

| Event                     | Level | Data Keys                                       |
|---------------------------|-------|-------------------------------------------------|
| `:artifact/created`       | info  | `:artifact-type`, `:artifact-id`, `:origin`     |
| `:artifact/versioned`     | debug | `:artifact-id`, `:old-version`, `:new-version`  |
| `:artifact/linked`        | trace | `:parent-id`, `:child-id`, `:link-type`         |
| `:artifact/validation`    | debug | `:artifact-id`, `:validator`, `:result`         |

#### System Events

| Event                     | Level | Data Keys                                       |
|---------------------------|-------|-------------------------------------------------|
| `:system/startup`         | info  | `:version`, `:config-hash`, `:plugins-loaded`   |
| `:system/shutdown`        | info  | `:reason`, `:graceful?`                         |
| `:system/config-changed`  | info  | `:changed-keys`, `:source`                      |
| `:system/plugin-loaded`   | info  | `:plugin-id`, `:version`, `:capabilities`       |
| `:system/health-check`    | debug | `:component`, `:status`, `:latency-ms`          |

---

## 3. Scenario Tracking

### 3.1 Purpose

Enable testing-in-production by tagging log entries with scenario metadata, allowing:

- Correlation of logs to specific test scenarios
- Filtering production logs for test analysis
- Replay and debugging of specific flows
- Canary evaluation of process changes

### 3.2 Scenario Schema

```clojure
{:scenario/id          uuid
 :scenario/name        string          ; human-readable identifier
 :scenario/tags        #{keyword}      ; categorization tags
 :scenario/created-at  inst
 :scenario/created-by  string          ; user or system identifier
 :scenario/config      map             ; scenario-specific configuration
 :scenario/expected    map             ; expected outcomes for validation
 :scenario/status      keyword}        ; :running, :passed, :failed, :cancelled
```

### 3.3 Scenario Tags (Standard)

| Tag                  | Meaning                                     |
|----------------------|---------------------------------------------|
| `:canary`            | Part of canary rollout evaluation           |
| `:shadow`            | Shadow mode comparison run                  |
| `:regression`        | Regression test scenario                    |
| `:smoke`             | Quick validation scenario                   |
| `:stress`            | Load/stress testing                         |
| `:chaos`             | Failure injection testing                   |
| `:golden-path`       | Expected happy path                         |
| `:error-recovery`    | Error handling validation                   |
| `:rollback`          | Rollback behavior testing                   |

### 3.4 Scenario API

```clojure
(defprotocol ScenarioTracker
  (start-scenario [this scenario-config]
    "Begin tracking a scenario, returns scenario-id")
  
  (tag-context [this scenario-id]
    "Returns context map to merge into log entries")
  
  (end-scenario [this scenario-id status summary]
    "Complete scenario tracking with final status")
  
  (query-scenario-logs [this scenario-id criteria]
    "Retrieve logs for a specific scenario")
  
  (compare-scenarios [this scenario-id-a scenario-id-b]
    "Compare outcomes of two scenario runs"))
```

---

## 4. Log Levels & Verbosity

### 4.1 Level Definitions

| Level   | Use Case                                              | Default In   |
|---------|-------------------------------------------------------|--------------|
| `:trace`| Detailed internal state (memory updates, links)       | Dev only     |
| `:debug`| Operational detail (prompts, iterations, gates)       | Dev + Staging|
| `:info` | Business events (phase changes, completions)          | All          |
| `:warn` | Recoverable issues (validation failures, retries)     | All          |
| `:error`| Failed operations requiring attention                 | All          |
| `:fatal`| System-level failures                                 | All          |

### 4.2 Dynamic Verbosity Control

The meta loop can adjust logging verbosity based on:

- Current error rate (increase on anomalies)
- Cost budget (reduce if logging costs spike)
- Active scenarios (increase for test runs)
- Specific workflow debugging (per-workflow override)

```clojure
{:logging/default-level :info
 :logging/overrides     {:workflow-id/abc123 :debug
                         :agent-role/implementer :trace}
 :logging/auto-escalate {:error-rate-threshold 0.1
                         :escalate-to :debug
                         :duration-minutes 15}}
```

---

## 5. Storage & Retention

### 5.1 Storage Tiers

| Tier     | Duration | Content                              | Query Speed |
|----------|----------|--------------------------------------|-------------|
| Hot      | 7 days   | All levels                           | < 100ms     |
| Warm     | 30 days  | :info and above + scenario-tagged    | < 1s        |
| Cold     | 1 year   | :warn and above + audit events       | < 30s       |
| Archive  | 7 years  | Audit-required events only           | Minutes     |

### 5.2 Audit Events (Always Retained)

- `:policy/human-approved`
- `:artifact/created` (for :code, :manifest, :image)
- `:outer/workflow-completed`
- `:meta/improvement-applied`
- `:system/config-changed`

### 5.3 Storage Port

```clojure
(defprotocol LogStore
  (append [this entry]
    "Append log entry, returns entry-id")
  
  (query [this criteria]
    "Query logs matching criteria, returns lazy seq")
  
  (aggregate [this criteria aggregation]
    "Aggregate logs (count, sum, avg, etc.)")
  
  (stream [this criteria callback]
    "Stream matching logs in real-time"))
```

---

## 6. Meta Loop Integration

### 6.1 Signal Extraction

The meta loop extracts signals from logs via aggregation queries:

```clojure
;; Example: Calculate review rejection rate
{:query {:event #{:agent/task-completed :agent/task-failed}
         :agent-role :reviewer
         :time-range [:last-24h]}
 :aggregate {:group-by :ctx/workflow-id
             :compute {:rejection-rate (/ (count-where :failed) (count))}}}

;; Example: Identify slow phases
{:query {:event :outer/phase-completed
         :time-range [:last-7d]}
 :aggregate {:group-by :phase
             :compute {:p95-duration (percentile :perf/duration-ms 95)
                       :avg-duration (avg :perf/duration-ms)}}}
```

### 6.2 Anomaly Detection

Log patterns that trigger meta loop attention:

| Pattern                          | Signal                  | Threshold        |
|----------------------------------|-------------------------|------------------|
| Spike in `:inner/escalated`      | Generation quality      | > 20% in 1hr     |
| Increase in `:policy/budget-exceeded` | Cost runaway       | > 5 in 1hr       |
| Cluster of `:agent/task-failed`  | Agent reliability       | > 10% error rate |
| `:inner/validation-failed` on same gate | Systematic gap   | > 3 consecutive  |

---

## 7. Implementation

### 7.1 Logger Interface

```clojure
(defprotocol Logger
  (log [this level category event data]
    "Emit structured log entry")
  
  (with-context [this context-map]
    "Return logger with additional context merged into all entries")
  
  (timed [this level category event f]
    "Execute f, log with duration"))

;; Convenience macros
(defmacro log-info [logger event data] ...)
(defmacro log-debug [logger event data] ...)
(defmacro with-span [logger span-name & body] ...)
```

### 7.2 Context Propagation

Context flows through the system via dynamic binding or explicit threading:

```clojure
(defn run-task [logger task agent]
  (let [ctx-logger (with-context logger
                     {:ctx/task-id (:task/id task)
                      :ctx/agent-id (:agent/id agent)
                      :ctx/phase (task->phase task)})]
    (log-info ctx-logger :agent/task-started
              {:agent-role (:agent/role agent)
               :task-type (:task/type task)})
    ...))
```

### 7.3 Output Formats

| Format       | Use Case                        | Example Target           |
|--------------|---------------------------------|--------------------------|
| EDN (native) | Internal storage, Clojure tools | PostgreSQL JSONB, files  |
| JSON         | External integrations           | Elasticsearch, Datadog   |
| OTLP         | Distributed tracing             | Jaeger, Tempo            |

---

## 8. Deliverables

### Phase 0 (Foundations)

- [ ] Log entry schema (Malli/spec)
- [ ] Event taxonomy definitions
- [ ] Scenario schema

### Phase 1 (Domain)

- [ ] Logger protocol
- [ ] Context propagation utilities
- [ ] Timed execution wrapper

### Phase 2 (Infrastructure)

- [ ] File-based EDN log store (dev)
- [ ] PostgreSQL log store (production)
- [ ] Log query engine

### Phase 3 (Integration)

- [ ] Meta loop signal extractors
- [ ] Anomaly detection rules
- [ ] Scenario tracker implementation

---

## 9. Open Questions

1. **Log shipping**: Push vs pull for external integrations?
2. **Sampling**: Should high-volume :trace/:debug events be sampled?
3. **PII handling**: Automatic redaction vs explicit marking?
4. **Cross-workflow correlation**: How to track artifacts that span workflows?
