<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# I — Autonomous Incident Diagnostics and Response Workflows

**Status:** Informative (non-normative)
**References:** N10 (Governed Tool Execution)

---

## Purpose

This document describes how to build incident diagnostic and response workflows
using the governed execution system defined in N10. It is an example of an
**operational workflow domain** — one of many possible consumers of governed
tool execution.

This is not normative. Implementations are not required to support incident
diagnostics. The patterns described here apply equally to other operational
domains (CI/CD automation, infrastructure provisioning, compliance remediation).

---

## 1. Incident Model

An incident represents an abnormal operational condition.

```clojure
{:incident/id         string
 :incident/source     keyword    ;; :monitoring :log-anomaly :user-report :health-check
 :incident/symptoms   map        ;; {:error-rate 0.28 :latency-ms 1200}
 :incident/environment keyword   ;; :dev :staging :prod
 :incident/service    string
 :incident/severity   keyword    ;; :critical :high :medium :low
 :incident/detected-at inst}
```

---

## 2. Diagnostic Workflow

A diagnostic workflow is a directed graph of investigative steps. All steps are
Class A (observational) — diagnostics never modify state.

### Investigation Graph

```text
detect anomaly
     │
     ▼
collect metrics ──────────────────┐
     │                             │
     ▼                             ▼
inspect deployments          inspect dependencies
     │                             │
     ▼                             ▼
analyze pod events           check database health
     │                             │
     └──────────┬──────────────────┘
                ▼
       correlate findings
                │
                ▼
       generate hypothesis
```

The graph MAY expand dynamically based on evidence (e.g., if database health
check reveals connection exhaustion, add connection pool analysis step).

### Diagnostic Agent Specializations

| Agent | Domain | Signals |
|-------|--------|---------|
| Infrastructure | Cluster/node health | Node events, scaling failures, network errors |
| Application | Service-level failures | Error logs, exception patterns, degraded endpoints |
| Deployment | Recent changes | Rollouts, config updates, feature flags |
| Dependency | Downstream failures | Service dependency errors, database connectivity |

### Diagnostic Evidence

```clojure
{:diagnosis/incident-id  string
 :diagnosis/symptoms     map
 :diagnosis/observations [keyword]    ;; [:pod-crashloop :database-timeouts]
 :diagnosis/recent-changes [string]   ;; ["deployment-v24"]
 :diagnosis/hypothesis
 {:root-cause  keyword               ;; :database-connection-exhaustion
  :confidence  double                ;; 0.83
  :supporting-evidence [map]}
 :diagnosis/recommended-actions [map]
 :diagnosis/created-at inst}
```

---

## 3. Remediation Workflows

Remediation executes operational fixes through the N10 governed execution pipeline.

### Remediation by Action Class

| Class | Examples | N10 Gate |
|-------|----------|----------|
| A | No remediation (diagnosis only) | — |
| B | Restart pods, requeue jobs, bounded scaling | Autonomous with postconditions |
| C | Rollback deployment, adjust configuration | Policy + possible approval |
| D | Delete infrastructure, reset databases | Human approval required |

### Example: Deployment Rollback

```text
1. Diagnostic agent identifies faulty deployment (Class A observations)
2. Generates remediation intent:
   {:intent/objective     "Rollback faulty deployment v24"
    :intent/environment   :prod
    :intent/target        {:target/service "payments-api"}
    :intent/allowed-actions [:k8s/rollback-deployment]
    :intent/rollback      {:type :deploy-forward :version "v23"}
    :intent/evidence      {:diagnosis-id "DIAG-1024"
                           :root-cause :faulty-config
                           :confidence 0.83}}
3. N10 verification pipeline:
   - Target resolver: identifies deployment/payments-api in production
   - Policy evaluator: Class C in prod → static analysis + dry-run
   - Rollback verifier: deploy-forward to v23 is viable
4. Validation: dry-run shows clean rollback, no dependency breaks
5. Capability issued: rollback payments-api deployment, 90s TTL
6. Capsule executes rollback
7. Postconditions monitored: error rate drops, latency normalizes
```

---

## 4. Post-Remediation Monitoring

After remediation, the system monitors outcomes:

```clojure
{:postcondition/expectations
 [{:metric "error_rate" :condition :lt :threshold 0.02}
  {:metric "p99_latency_ms" :condition :lt :threshold 500}
  {:metric "availability" :condition :gt :threshold 0.999}]
 :postcondition/timeout-seconds 300
 :postcondition/on-failure :rollback}
```

If postconditions fail, the system triggers the declared rollback action.

---

## 5. Learning Loop

Incident outcomes feed back into the system:

- Successful diagnoses become knowledge base patterns (N1 §2.10)
- Remediation success/failure rates inform trust level progression (N10 §14)
- Failed hypotheses refine diagnostic heuristics
- Operator overrides capture human expertise

This learning loop is the existing N1 knowledge base mechanism applied to
operational data. No additional architectural primitives are needed.

---

## 6. Example: Full Incident Lifecycle

```text
12:00:00  Monitoring alert: payments-api error rate 28%
12:00:05  Diagnostic workflow starts (all Class A)
12:00:10  Metrics collected: error spike correlates with deployment v24
12:00:15  Log analysis: NullPointerException in new config parser
12:00:20  Deployment history: v24 deployed 5 minutes ago
12:00:25  Diagnosis: faulty config in v24, confidence 0.83
12:00:30  Remediation intent: rollback to v23 (Class C)
12:00:32  Verification: target resolved, policy allows, rollback viable
12:00:35  Validation: dry-run clean
12:00:37  Capability issued: rollback payments-api, 90s TTL
12:00:38  Capsule executes: kubectl rollout undo deployment/payments-api
12:00:45  Postcondition monitoring starts
12:02:00  Error rate: 0.8% (within threshold)
12:02:00  Postcondition passed — remediation successful
12:02:01  Evidence bundle generated, knowledge captured
```

Total MTTD: 25 seconds. Total MTTR: 2 minutes.
