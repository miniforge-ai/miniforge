# I — Extended Validation Strategies

**Status:** Informative (non-normative)
**References:** N10 §5.3

---

## Purpose

This document describes validation strategies beyond the normative requirements
of N10 §5 (static analysis + provider dry-run). These are optional capabilities
that implementations MAY support via the `ValidationAdapter` and `EnvironmentAdapter`
interfaces.

---

## 1. Formal Model Verification

Uses formal modeling languages to verify safety invariants before execution.

### Supported Tools

- **TLA+** — temporal logic of actions; verifies state machine properties
- **Quint** — executable specification language; state machine simulation

### Use Cases

- Verify capability scoping cannot violate crown jewel invariants
- Verify rollback sequencing under concurrent execution
- Verify that authority boundaries hold across multi-step workflows
- Verify liveness properties (workflow eventually completes or fails)

### Adapter Interface

```clojure
;; Formal model adapter (implements ValidationAdapter)
{:tool/id          :formal/tla-plus
 :tool/type        :external
 :tool/action-class :A             ;; verification is read-only
 :tool/config
 {:external/command ["tla-verify"]
  :external/timeout-ms 120000}
 :tool/capabilities #{:validation/formal-verification}}
```

### Invariant Example

```text
Invariant: NoDoubleAuthority
  For all capabilities c1, c2 held by agent A:
    NOT (c1.resource IN crown_jewels AND
         c2.resource IN paired_recovery(c1.resource))
```

---

## 2. Ephemeral Environment Rehearsal

Runs workflows in isolated, disposable environments before production execution.

### Integration: Shipyard

Shipyard provisions preview environments with full service stacks. Integration
uses the `EnvironmentAdapter` protocol from N10 §5.3.

```clojure
;; Shipyard MCP server registration
{:tool/id          :shipyard/environments
 :tool/type        :mcp
 :tool/name        "Shipyard Environment Manager"
 :tool/action-class :B
 :tool/verb-classes
 {:verb/create-environment  :B
  :verb/deploy             :B
  :verb/execute-workflow   :B
  :verb/destroy-environment :A
  :verb/get-status         :A}
 :tool/config
 {:mcp/command ["npx" "@shipyard/mcp-server"]
  :mcp/transport :stdio}
 :tool/capabilities #{:validation/ephemeral-environment
                      :validation/integration-test}}
```

### Workflow

```text
1. Create Shipyard environment (matching production topology)
2. Deploy current state + proposed changes
3. Execute OIR actions against ephemeral environment
4. Observe postconditions
5. Record results as validation artifacts
6. Destroy environment
```

### What Rehearsal Validates

- Service interaction correctness
- Deployment sequencing
- Configuration compatibility
- Network policy effects
- Resource limit behavior

### Guidance

Ephemeral rehearsal is most valuable for:

- Multi-service orchestration (Class C)
- First-time execution of new workflow types
- Production environments with crown jewel intersections

Cost: high (provisions real infrastructure). Use when provider dry-run is
insufficient — typically when the change involves cross-service interactions
that a single-resource dry-run cannot model.

---

## 3. Synthetic Data Rehearsal

Synthetic or transformed datasets simulate realistic state without exposing
sensitive data.

### Integration: Tonic

Tonic generates structurally accurate synthetic data from production schemas.

```clojure
{:tool/id          :tonic/synthetic-data
 :tool/type        :external
 :tool/name        "Tonic Synthetic Data Generator"
 :tool/action-class :A             ;; data generation is read-only
 :tool/config
 {:external/command ["tonic" "generate"]
  :external/timeout-ms 600000}
 :tool/capabilities #{:validation/synthetic-data}}
```

### Use Cases

- Database migration workflow validation
- Data processing pipeline testing
- Repair script verification
- Diagnostic query validation against realistic data shapes

### What Synthetic Data Preserves

- Schema structure and constraints
- Relational integrity (foreign keys, references)
- Statistical distributions (cardinality, value ranges)
- Edge cases (nulls, empty sets, boundary values)

---

## 4. Bounded Canary Execution

Controlled execution on real infrastructure with limited blast radius.

### Scope Limiting

- Single shard / partition
- One replica / instance
- One node pool
- Single tenant
- Percentage-based traffic split

### Prerequisites

- Capability must be scoped to canary target only
- Postcondition monitoring must be active
- Automatic rollback must be configured
- Blast radius analyzer must confirm canary isolation

### Guidance

Canary execution is the highest-confidence validation but carries real risk.
Reserve for:

- Trust Level L3+ workflows (N10 §14)
- Actions where dry-run output is known to diverge from actual behavior
- Final validation before fleet-wide rollout

---

## 5. Validation Strategy Selection

Policy packs can encode validation strategy requirements:

```clojure
{:rule/id          :val/multi-service-rehearsal
 :rule/title       "Multi-service changes require rehearsal"
 :rule/severity    :high
 :rule/category    "validation-strategy"
 :rule/applies-to  {:action-classes #{:C}
                    :tags #{:multi-service}}
 :rule/detection   {:type :action-classification}
 :rule/enforcement {:action   :require-approval
                    :message  "Multi-service Class C actions should use rehearsal"
                    :remediation "Run via Shipyard ephemeral environment"}}
```

The validation ladder is not a fixed sequence. Policy determines which strategies
are required based on action class, environment, resource sensitivity, and
workflow maturity.
