# N10 — Governed Tool Execution

**Version:** 0.1.0-draft
**Date:** 2026-03-06
**Status:** Draft
**Conformance:** MUST

---

## 1. Purpose and Scope

This specification defines the architecture for safe, bounded, and auditable execution
of tool actions by Miniforge agents against external systems.

The system MUST enable useful autonomous workflows while preventing catastrophic
operations such as:

- deletion of production databases or backups
- modification of identity, audit, or encryption systems
- large-scale unintended infrastructure changes
- credential leakage or privilege escalation

The core model: agents express **operational intent**, which is verified, classified,
policy-evaluated, and executed inside **isolated capsules** with **ephemeral capabilities**.

### 1.1 What This Specification Defines

- Operational intent representation (§2)
- Action classification and tool-declared risk (§3)
- Verification pipeline (§4)
- Validation requirements (§5)
- Capability model and lifecycle (§6)
- Execution capsule isolation (§7)
- Crown jewel protection (§8)
- Postcondition monitoring (§9)
- Safety invariants (§10)
- External system integration (§11)
- Audit integration (§12)
- Conformance requirements (§13)

### 1.2 What This Specification Does Not Define

- Fleet governance and trust scoring (future N12 enterprise extension)
- Specific diagnostic or incident workflows (informative: I-INCIDENT-DIAGNOSTICS)
- Extended validation strategies beyond static analysis and provider dry-run
  (informative: I-VALIDATION-STRATEGIES)

### 1.3 Design Principles

1. **Intent over command** — agents declare what they want to achieve, not which
   commands to run
2. **Tool-declared risk** — action classification is a property of the tool definition,
   not the agent's judgment
3. **Progressive trust** — start with observation, earn autonomy through demonstrated
   safety
4. **Fail closed** — deny when uncertain; never allow by default
5. **Capsule isolation** — every tool execution runs in a bounded, destroyable context
6. **Evidence everywhere** — every action produces audit evidence linked to N6 bundles

---

## 2. Operational Intent

### 2.1 Intent Model

Agents express **intent**, not direct tool commands. Intent describes the desired
outcome, target, scope, and constraints. The orchestrator (or a meta-agent advising
the orchestrator) compiles intent into a verified action graph.

```clojure
{:intent/id            uuid       ; REQUIRED: unique identifier
 :intent/objective     string     ; REQUIRED: what the agent wants to achieve
 :intent/environment   keyword    ; REQUIRED: :dev :staging :prod
 :intent/target
 {:target/service     string     ; REQUIRED: target service or resource
  :target/region      string     ; OPTIONAL: infrastructure region
  :target/namespace   string}    ; OPTIONAL: e.g. Kubernetes namespace
 :intent/allowed-actions  [keyword]  ; REQUIRED: actions the agent requests
 :intent/forbidden-actions [keyword] ; OPTIONAL: explicit exclusions
 :intent/evidence     map        ; REQUIRED: supporting data for the action
 :intent/rollback     map        ; REQUIRED for Class B/C: recovery plan
 :intent/max-blast-radius map    ; OPTIONAL: self-declared scope limits
 :intent/workflow-id  uuid       ; REQUIRED: enclosing workflow
 :intent/phase        keyword    ; REQUIRED: current workflow phase
 :intent/created-at   inst}      ; REQUIRED
```

### 2.2 Operational IR

Verified intent is compiled into **Operational IR** — the canonical representation
of what will actually execute.

```clojure
{:oir/id               uuid
 :oir/intent-id        uuid       ; REQUIRED: source intent
 :oir/action-class     keyword    ; REQUIRED: resolved class (§3)
 :oir/actions
 [{:action/tool-id    keyword    ; REQUIRED: tool registry ID
   :action/verb       keyword    ; REQUIRED: specific operation
   :action/args       map        ; REQUIRED: resolved arguments
   :action/class      keyword    ; REQUIRED: action class from tool registry
   :action/rollback   map}]      ; REQUIRED for B/C: per-action rollback
 :oir/risk-score       double     ; REQUIRED: 0.0–1.0
 :oir/approval-mode    keyword    ; REQUIRED: :autonomous :require-approval :deny
 :oir/validation       map        ; REQUIRED: validation artifacts (§5)
 :oir/policy-result    map        ; REQUIRED: policy evaluation outcome (§4.2)
 :oir/compiled-at      inst}
```

The OIR `action-class` is the **maximum** class across all constituent actions.
An OIR containing one Class C action and three Class A actions is classified Class C.

---

## 3. Action Classification

### 3.1 Action Classes

| Class | Name | Reversibility | Autonomous Execution | Examples |
|-------|------|--------------|---------------------|----------|
| A | Observational | N/A (read-only) | Always allowed | logs, metrics, describe, list |
| B | Ephemeral Reversible | Auto-rollback exists | Allowed when rollback verified | restart pod, requeue job, bounded scale |
| C | Persistent Rollbackable | Manual rollback possible | Policy evaluation + possible approval | deployment rollout, feature flag, config change |
| D | Destructive Stateful | Difficult/impossible | Human approval REQUIRED | database deletion, storage removal |
| E | Irreversible | No recovery | Autonomous execution PROHIBITED | backup deletion, key destruction, IAM root changes |

### 3.2 Classification Ownership

Action classification MUST be a property of the **tool definition**, not the agent's
intent.

The tool registry schema (N1 §2.3, `tool-registry` component) MUST be extended with:

```clojure
;; Extension to ToolConfig schema
{:tool/action-class    keyword    ; REQUIRED: :A :B :C :D :E (default class)
 :tool/verb-classes               ; OPTIONAL: per-verb overrides
 {:verb/describe  :A
  :verb/scale     :B
  :verb/rollout   :C
  :verb/delete    :D}
 :tool/crown-jewel-interactions   ; OPTIONAL: crown jewel resource types affected
 #{:backup :encryption-keys :iam :audit-logs}}
```

**Classification rules (normative):**

1. Tool authors MUST declare `:tool/action-class` for every registered tool.
2. Tools without a declared class MUST default to Class D (fail safe).
3. Agents MUST NOT reclassify actions. The orchestrator MUST reject intent where
   requested actions exceed the tool-declared class.
4. Per-verb overrides (`:tool/verb-classes`) allow a single tool to expose both
   read and write operations with appropriate classification.

### 3.3 Class Determination for Composite Actions

When an OIR contains multiple actions:

1. The OIR class is the **maximum** class across all actions (A < B < C < D < E).
2. If any action is Class E, the entire OIR is Class E (prohibited).
3. If any action is Class D, the entire OIR requires human approval regardless of
   other actions.

---

## 4. Verification Pipeline

### 4.1 Required Verifiers

Every Operational Intent MUST pass through three required verification stages:

#### 4.1.1 Target Resolver

Determines the exact resources affected by the intent.

- MUST resolve symbolic targets (e.g., "payments-api") to concrete resource identifiers
  (e.g., specific pod names, deployment ARNs)
- MUST fail verification if target cannot be resolved
- MUST enumerate all resources in the blast radius

#### 4.1.2 Policy Evaluator

Evaluates the Operational IR against active policy packs (N4).

- MUST use the existing policy pack evaluation engine
- MUST consider: environment, action class, resource tags, rollback availability,
  crown jewel intersections, risk score
- Returns one of: `:allow`, `:allow-with-constraints`, `:require-approval`, `:deny`,
  `:quarantine`

Policy evaluation for tool execution uses the same `PackManifest`, `Rule`, and
`RuleEnforcementConfig` schemas from `policy-pack/schema.clj`. Tool execution policies
are policy packs with `:rule/applies-to` targeting action classes and tool IDs rather
than file globs and task types.

```clojure
;; Example: tool execution policy rule
{:rule/id          :ops/no-prod-delete
 :rule/title       "No autonomous production deletions"
 :rule/description "Class D/E actions in production require human approval"
 :rule/severity    :critical
 :rule/category    "operational-safety"
 :rule/applies-to
 {:action-classes  #{:D :E}
  :environments    #{:prod}
  :phases          #{:implement :remediate}}
 :rule/detection   {:type :action-classification}
 :rule/enforcement {:action   :hard-halt
                    :message  "Destructive actions in production require approval"
                    :approvers [:human :security]}}
```

#### 4.1.3 Rollback Verifier

Confirms a viable recovery path exists for Class B and C actions.

- MUST verify the declared rollback action is itself a valid tool invocation
- MUST verify the rollback action has equal or lower action class than the
  forward action
- MUST fail verification for Class B/C actions without a verified rollback
- Class A actions do not require rollback verification
- Class D/E actions require human-supplied rollback plans

### 4.2 Verification Adapter Hooks

Implementations MAY register additional verifiers via an adapter interface:

```clojure
(defprotocol VerificationAdapter
  (verify [adapter operational-ir context]
    "Evaluate the OIR and return verification result.
     Returns {:verified? bool :findings [...] :confidence double}"))
```

Example additional verifiers (not required for conformance):

- **Blast Radius Analyzer** — computes dependency closure and impact scope
- **Simulation Executor** — runs dry-run or plan simulations
- **Adversarial Reviewer** — LLM-based edge case detection

If any registered verifier returns `{:verified? false}`, execution MUST halt.

### 4.3 Verification Pipeline Sequence

```text
Operational Intent
     │
     ▼
Target Resolver ──── fail? ──► DENY
     │
     ▼
Policy Evaluator ─── deny? ──► DENY
     │               approval? ──► AWAIT APPROVAL
     ▼
Rollback Verifier ── fail? ──► DENY (Class B/C)
     │
     ▼
[Adapter Hooks] ──── fail? ──► DENY
     │
     ▼
Validation (§5)
     │
     ▼
Capability Issuance (§6)
```

---

## 5. Validation Requirements

### 5.1 Required Validation

All Operational IRs MUST undergo static analysis. Higher-risk actions require
additional validation.

#### 5.1.1 Static Analysis (REQUIRED — all classes)

Structural analysis of the Operational IR.

Checks MUST include:

- Resource target resolution validity
- Action classification correctness (matches tool registry)
- Policy compliance (no rule violations)
- Forbidden action intersection (intent excludes are honored)
- Crown jewel intersection detection (§8)
- Rollback presence for Class B/C

#### 5.1.2 Provider Dry-Run (REQUIRED — Class C and above)

Uses provider tooling to preview operations before execution.

- Kubernetes: `--dry-run=server`
- Terraform: `terraform plan`
- AWS: CloudFormation change sets
- Docker: build simulation

Dry-run MUST verify:

- No unexpected resource mutations
- No dependency breakage
- Blast radius within declared limits

Provider dry-run is the primary validation mechanism. The system leverages provider
tooling that already models the world rather than building a parallel world model.

### 5.2 Validation Policy

Validation requirements are determined by action class and environment:

| Action Class | Environment | Required Validation |
|-------------|-------------|-------------------|
| A | Any | Static analysis |
| B | dev/staging | Static analysis |
| B | prod | Static analysis + provider dry-run |
| C | Any | Static analysis + provider dry-run |
| D | Any | Static analysis + provider dry-run + human review |
| E | Any | PROHIBITED (autonomous execution denied) |

### 5.3 Extended Validation Adapter Interface

Implementations MAY support additional validation strategies via adapters.
See I-VALIDATION-STRATEGIES for guidance on:

- Formal model verification (TLA+, Quint)
- Ephemeral environment rehearsal (e.g., Shipyard)
- Synthetic data rehearsal (e.g., Tonic)
- Bounded canary execution

```clojure
(defprotocol ValidationAdapter
  (validate [adapter operational-ir context]
    "Execute validation and return result.
     Returns {:validated? bool :confidence double :artifacts [...]}"))

(defprotocol EnvironmentAdapter
  (create-environment [adapter spec]
    "Provision an ephemeral environment for rehearsal.
     Returns {:environment-id string :endpoints map :ttl-seconds int}")
  (execute-in-environment [adapter env-id operational-ir]
    "Run the OIR in the ephemeral environment.
     Returns {:result map :observations [...] :artifacts [...]}")
  (destroy-environment [adapter env-id]
    "Tear down the ephemeral environment."))
```

These adapters are registered as tools in the tool registry with type `:external`
or `:mcp`. This is the mechanism for plugging in third-party platforms like Shipyard
— they are tools that provide validation capabilities, registered and invoked through
the standard tool contract.

### 5.4 Validation Artifacts

Validation produces artifacts attached to the Operational IR and stored in the
audit ledger (N6).

```clojure
{:validation/id        uuid
 :validation/oir-id    uuid       ; REQUIRED: which OIR was validated
 :validation/methods   [keyword]  ; REQUIRED: :static-analysis :provider-dry-run etc.
 :validation/confidence double    ; REQUIRED: 0.0–1.0
 :validation/risk       double    ; REQUIRED: 0.0–1.0
 :validation/status     keyword   ; REQUIRED: :approved :denied :requires-approval
 :validation/findings   [map]     ; REQUIRED: issues found (may be empty)
 :validation/artifacts  [map]     ; OPTIONAL: dry-run output, simulation results
 :validation/validated-at inst}
```

---

## 6. Capability Model

### 6.1 Capability Structure

Capabilities represent **temporary, bounded authority** granted for a specific
operation. Agents never receive long-lived credentials.

```clojure
{:capability/id        string     ; REQUIRED: unique identifier
 :capability/oir-id    uuid       ; REQUIRED: the OIR this capability serves
 :capability/resource  map        ; REQUIRED: scoped resource target
 :capability/verbs     [keyword]  ; REQUIRED: permitted operations
 :capability/limits    map        ; OPTIONAL: parameter bounds
 :capability/ttl-seconds int      ; REQUIRED: maximum lifetime
 :capability/policy-bindings [map] ; REQUIRED: policy rules that authorized this
 :capability/revocation-handle string ; REQUIRED: for immediate revocation
 :capability/issued-at  inst
 :capability/expires-at inst}
```

Example:

```clojure
{:capability/id "cap-2026-03-06-a1b2c3"
 :capability/oir-id #uuid "..."
 :capability/resource {:service "payments-api" :namespace "production"}
 :capability/verbs [:scale]
 :capability/limits {:replicas [3 6]}   ;; scale between 3 and 6 only
 :capability/ttl-seconds 90
 :capability/policy-bindings [{:rule/id :ops/bounded-scaling}]
 :capability/revocation-handle "rev-a1b2c3"
 :capability/issued-at #inst "2026-03-06T12:00:00Z"
 :capability/expires-at #inst "2026-03-06T12:01:30Z"}
```

### 6.2 Capability Lifecycle

```text
REQUEST ──► VALIDATE ──► ISSUE ──► EXECUTE ──► EXPIRE
                                      │
                                   REVOKE (at any point after issuance)
```

1. **Request** — orchestrator requests capability based on approved OIR
2. **Validate** — capability broker confirms OIR is approved and policy allows issuance
3. **Issue** — scoped credentials generated; TTL clock starts
4. **Execute** — capsule uses capability (§7)
5. **Expire** — automatic on TTL; credentials invalidated
6. **Revoke** — immediate termination by policy engine, operator, or anomaly detection

### 6.3 Capability Broker

The Capability Broker is a component that issues capabilities after policy approval.

Implementations MUST:

1. Never issue capabilities exceeding permissions granted by policy evaluation
2. Enforce TTL — capabilities MUST auto-expire
3. Generate scoped credentials that are valid only for the declared resource and verbs
4. Track all active capabilities for revocation
5. Attach policy bindings showing which rules authorized issuance
6. Log all issuance and revocation events to the audit ledger

### 6.4 Revocation

Capabilities MAY be revoked by:

- Policy engine (rule violation detected during execution)
- Human operator (manual intervention)
- Anomaly detection (unexpected behavior observed)
- Postcondition failure (§9)
- Timeout (TTL exceeded)

Revocation MUST immediately terminate any active execution capsule using
the revoked capability.

---

## 7. Execution Capsules

### 7.1 Capsule Model

All tool operations with action class B or higher MUST execute inside capsules.
Class A (observational) operations MAY execute in capsules; implementations SHOULD
make this configurable for compliance use cases where full audit trails are required.

Capsules provide:

- Sandboxed runtime environment
- Scoped credentials (from capability)
- Restricted filesystem access
- Restricted network access
- Execution time limits
- Full audit logging

Capsules are destroyed after execution completes (success or failure).

### 7.2 Capsule Isolation Requirements

Implementations MUST enforce:

**Filesystem:**

- Capsule has access to: workspace directory, temporary storage, read-only system tools
- Capsule MUST NOT have access to: host filesystem, other capsules, credential stores

**Network:**

- Capsule may connect to: declared target service, telemetry endpoints
- Capsule MUST NOT connect to: undeclared services, external networks (unless explicitly
  in capability scope)

**Commands:**

- Capsule may execute: tools declared in capability verbs
- Capsule MUST NOT execute: undeclared tools, shell escapes, privilege escalation

**Time:**

- Capsule MUST enforce execution time limit from capability TTL
- Capsule MUST self-terminate when TTL expires

### 7.3 Capsule Implementation Strategies

Implementations MAY use any isolation mechanism that satisfies §7.2:

- Container isolation (Docker, Podman)
- Virtual machine isolation
- Process isolation with seccomp/AppArmor
- WASM sandboxing
- Cloud function isolation (Lambda, Cloud Run)

The choice of isolation mechanism is an implementation detail, not a normative
requirement, as long as the isolation properties in §7.2 are satisfied.

---

## 8. Crown Jewel Protection

### 8.1 Crown Jewel Registry

Certain resources require elevated protection. Organizations declare **crown jewels**
— resources whose compromise would cause catastrophic damage.

```clojure
{:crown-jewel/id       string
 :crown-jewel/type     keyword    ; REQUIRED: :backup :encryption-keys :iam
                                  ;           :audit-logs :secrets-manager
                                  ;           :primary-database :recovery-plane
 :crown-jewel/resource map        ; REQUIRED: resource identifier
 :crown-jewel/protection-class keyword ; REQUIRED: :critical :high
 :crown-jewel/paired-with [string]}    ; OPTIONAL: related crown jewels
                                       ;   (e.g., primary ↔ backup pair)
```

### 8.2 Crown Jewel Invariants

The system MUST enforce:

1. **Separation of authority:** A single capability MUST NOT grant access to both
   a primary system and its recovery plane (e.g., database and its backups).
2. **Elevated validation:** Any OIR intersecting crown jewel resources MUST undergo
   the maximum validation level for its class, plus human approval regardless of class.
3. **No autonomous mutation:** Crown jewel resources MUST NOT be mutated by autonomous
   agent action. Human approval is always required.
4. **Transitive protection:** If resource A is a crown jewel and resource B's failure
   would compromise resource A, then resource B inherits crown jewel protection.

### 8.3 Crown Jewel Detection

During verification (§4), the Target Resolver MUST check resolved resources against
the Crown Jewel Registry.

- If intersection detected: verification result MUST include
  `{:crown-jewel-intersection true :crown-jewels [...]}`
- Policy evaluation MUST treat crown jewel intersection as `:require-approval`
  at minimum, regardless of action class

### 8.4 Enforcement Mechanics

Crown jewel checks are implemented as policy pack rules (N4).

```clojure
{:rule/id          :crown/no-dual-authority
 :rule/title       "No dual primary/recovery authority"
 :rule/description "A capability must not span primary and recovery systems"
 :rule/severity    :critical
 :rule/category    "crown-jewel-protection"
 :rule/applies-to  {:crown-jewel-types #{:primary-database :backup
                                          :encryption-keys :recovery-plane}}
 :rule/detection   {:type :action-classification
                    :custom-fn 'ai.miniforge.policy-pack.crown/detect-dual-authority}
 :rule/enforcement {:action    :hard-halt
                    :message   "Cannot hold authority over primary and recovery systems"
                    :approvers [:security]}}
```

---

## 9. Postcondition Monitoring

### 9.1 Postcondition Model

Every executed action MUST attach postcondition observers that verify expected outcomes.

```clojure
{:postcondition/action-id  uuid     ; REQUIRED: which action to observe
 :postcondition/expectations
 [{:metric    string                 ; REQUIRED: what to measure
   :condition keyword                ; REQUIRED: :lt :gt :eq :within
   :threshold any                    ; REQUIRED: target value
   :tolerance any}]                  ; OPTIONAL: acceptable variance
 :postcondition/timeout-seconds int  ; REQUIRED: how long to observe
 :postcondition/on-failure     keyword ; REQUIRED: :rollback :alert :escalate
 :postcondition/rollback-ref   map}    ; REQUIRED if on-failure is :rollback
```

Example:

```clojure
{:postcondition/action-id #uuid "..."
 :postcondition/expectations
 [{:metric "error_rate" :condition :lt :threshold 0.02}
  {:metric "p99_latency_ms" :condition :lt :threshold 500}]
 :postcondition/timeout-seconds 300
 :postcondition/on-failure :rollback
 :postcondition/rollback-ref {:action/tool-id :k8s/deployment
                               :action/verb :rollback}}
```

### 9.2 Postcondition Requirements

Implementations MUST:

1. Attach postconditions to every Class B and C action
2. Monitor postconditions for the declared timeout period
3. Trigger rollback automatically when postconditions fail and `:on-failure` is
   `:rollback`
4. Emit postcondition events to the event stream (N3)
5. Record postcondition results in the audit ledger (N6)

---

## 10. Safety Invariants

The following invariants MUST be enforced by the system. Violation of any invariant
MUST halt execution immediately.

| ID | Invariant |
|----|-----------|
| SI-1 | No autonomous Class D/E operations in production environments |
| SI-2 | No single capability may span a primary system and its recovery plane |
| SI-3 | No wildcard resource mutation (actions MUST target specific resources) |
| SI-4 | No Class B/C execution without a verified rollback path |
| SI-5 | No modification of identity or audit systems by autonomous agents |
| SI-6 | No execution without validated Operational IR |
| SI-7 | No capability issuance without policy evaluation |
| SI-8 | No capsule execution beyond capability TTL |
| SI-9 | No credential persistence beyond capsule lifetime |
| SI-10 | Revocation MUST terminate execution within 5 seconds |

---

## 11. External System Integration

### 11.1 Tool Integration Model

External systems integrate with governed execution as **tools** registered in the
tool registry (N1 §2.3). This includes:

- Infrastructure providers (Kubernetes, AWS, Terraform)
- Validation platforms (Shipyard, Tonic)
- Monitoring systems (Datadog, Prometheus)
- MCP servers providing operational capabilities

### 11.2 MCP and SaaS Platform Integration

Third-party platforms that provide capabilities beyond simple tool invocation — such
as ephemeral environment provisioning (Shipyard), synthetic data generation (Tonic),
or full SaaS workflows — integrate through the tool registry as `:mcp` or `:external`
tool types.

```clojure
;; Example: Shipyard as an external validation tool
{:tool/id          :shipyard/environments
 :tool/type        :mcp
 :tool/name        "Shipyard Environment Manager"
 :tool/description "Provisions ephemeral environments for workflow rehearsal"
 :tool/action-class :B            ;; environment creation is ephemeral/reversible
 :tool/verb-classes
 {:verb/create-environment  :B
  :verb/deploy-to-environment :B
  :verb/execute-workflow    :B    ;; rehearsal, not production
  :verb/destroy-environment :A}   ;; cleanup is observational
 :tool/config
 {:mcp/command ["npx" "@shipyard/mcp-server"]
  :mcp/transport :stdio}
 :tool/capabilities #{:validation/ephemeral-environment}
 :tool/tags #{:validation :environment :rehearsal}}
```

The key distinction: **capabilities** are what the system can do (ephemeral environment
provisioning, synthetic data generation). **Tools** are the specific integrations that
provide those capabilities. A capability like `:validation/ephemeral-environment` may
be fulfilled by Shipyard, by an internal environment system, or by a custom MCP server.

Implementations SHOULD:

1. Register third-party platforms as tools with appropriate action classes
2. Use tool capabilities to match validation requirements to available providers
3. Allow multiple tools to provide the same capability (swappable backends)
4. Apply the same verification pipeline to validation tool invocations (recursion
   is bounded: validation tools are Class A/B, so their own validation is lightweight)

### 11.3 Adapter Registration

Validation and environment adapters (§5.3) register through the tool registry:

```clojure
;; Tool with adapter protocol implementation
{:tool/id          :shipyard/validation-adapter
 :tool/type        :external
 :tool/name        "Shipyard Validation Adapter"
 :tool/action-class :B
 :tool/config
 {:external/command ["shipyard" "validate"]
  :external/timeout-ms 300000}
 :tool/capabilities #{:validation/ephemeral-environment
                      :validation/integration-test}
 :tool/metadata
 {:adapter/protocols [:ValidationAdapter :EnvironmentAdapter]
  :adapter/supported-environments [:kubernetes :docker-compose]}}
```

---

## 12. Audit Integration

### 12.1 Required Audit Events

All governed execution operations MUST emit events to the event stream (N3) and
produce evidence for evidence bundles (N6).

| Event | Trigger | Data |
|-------|---------|------|
| `:intent/created` | Agent expresses intent | Intent structure |
| `:intent/compiled` | OIR generated from intent | OIR structure |
| `:verification/started` | Verification pipeline begins | OIR ID, verifiers |
| `:verification/completed` | All verifiers pass | Results, findings |
| `:verification/failed` | Any verifier fails | Failure details |
| `:validation/completed` | Validation artifacts produced | Validation result |
| `:capability/issued` | Capability broker issues credential | Capability (redacted) |
| `:capability/revoked` | Capability revoked | Reason, revocation source |
| `:capsule/started` | Execution capsule created | Capsule ID, tool, args |
| `:capsule/completed` | Capsule execution finished | Result, duration |
| `:capsule/terminated` | Capsule killed (revocation/TTL) | Reason |
| `:postcondition/passed` | Postcondition check succeeds | Metrics observed |
| `:postcondition/failed` | Postcondition check fails | Expected vs actual |
| `:rollback/triggered` | Rollback initiated | Trigger reason |
| `:rollback/completed` | Rollback finished | Success/failure |

### 12.2 Evidence Bundle Integration

Governed execution produces a sub-bundle within the workflow's evidence bundle:

```clojure
{:evidence/type           :governed-execution
 :evidence/intent-id      uuid
 :evidence/oir-id         uuid
 :evidence/action-class   keyword
 :evidence/policy-result  map
 :evidence/validation     map
 :evidence/capability-id  string
 :evidence/capsule-result map
 :evidence/postconditions [map]
 :evidence/duration-ms    long
 :evidence/timestamp      inst}
```

---

## 13. Conformance Requirements

### 13.1 Tool Registration

| ID | Level | Requirement |
|----|-------|-------------|
| N10.TR.1 | MUST | Every registered tool MUST declare `:tool/action-class` |
| N10.TR.2 | MUST | Tools without declared class MUST default to Class D |
| N10.TR.3 | MUST NOT | Agents MUST NOT reclassify tool actions |

### 13.2 Verification

| ID | Level | Requirement |
|----|-------|-------------|
| N10.VF.1 | MUST | All intents MUST pass target resolution, policy evaluation, and rollback verification |
| N10.VF.2 | MUST | Execution MUST halt if any required verifier fails |
| N10.VF.3 | MUST | Class B/C actions MUST have verified rollback paths |

### 13.3 Validation

| ID | Level | Requirement |
|----|-------|-------------|
| N10.VL.1 | MUST | All OIRs MUST undergo static analysis |
| N10.VL.2 | MUST | Class C and above MUST undergo provider dry-run |
| N10.VL.3 | SHOULD | Implementations SHOULD support the ValidationAdapter interface for extended strategies |

### 13.4 Capabilities

| ID | Level | Requirement |
|----|-------|-------------|
| N10.CP.1 | MUST | Capabilities MUST be ephemeral with enforced TTL |
| N10.CP.2 | MUST | Capabilities MUST NOT exceed policy-granted permissions |
| N10.CP.3 | MUST | Revocation MUST terminate execution within 5 seconds |
| N10.CP.4 | MUST | Credential material MUST NOT persist beyond capsule lifetime |

### 13.5 Execution

| ID | Level | Requirement |
|----|-------|-------------|
| N10.EX.1 | MUST | Class B+ actions MUST execute in capsules |
| N10.EX.2 | MUST | Capsules MUST enforce filesystem, network, and time isolation |
| N10.EX.3 | MAY | Class A actions MAY execute in capsules (configurable) |

### 13.6 Safety

| ID | Level | Requirement |
|----|-------|-------------|
| N10.SF.1 | MUST | All safety invariants (§10) MUST be enforced |
| N10.SF.2 | MUST | Crown jewel resources MUST NOT be autonomously mutated |
| N10.SF.3 | MUST | Postconditions MUST be attached to Class B/C actions |

### 13.7 Audit

| ID | Level | Requirement |
|----|-------|-------------|
| N10.AU.1 | MUST | All events in §12.1 MUST be emitted to the event stream |
| N10.AU.2 | MUST | Governed execution evidence MUST link to N6 evidence bundles |

---

## 14. Trust Level Progression

Implementations SHOULD support progressive trust levels that gate autonomous
execution permissions. Trust levels are per-workflow, per-environment.

| Level | Name | Permitted Actions | Advancement Criteria |
|-------|------|------------------|---------------------|
| L0 | Observation | Class A only | Default for new workflows |
| L1 | Recommendation | Class A; suggest B/C for human execution | 10+ successful observations |
| L2 | Bounded Execution | Class A/B autonomous; Class C with approval | 25+ successful B actions, 0 rollbacks in last 10 |
| L3 | Controlled Autonomy | Class A/B/C autonomous with postconditions | 50+ successful C actions, <2% rollback rate |
| L4 | Domain Autonomy | Full Class A/B/C; Class D with approval | Organization-specific criteria |

Trust levels are informational in OSS. Fleet/Enterprise deployments (future N12)
enforce them via centralized trust registries.

---

## 15. Reference Architecture

```text
Agent
  │
  ▼
Operational Intent (§2)
  │
  ▼
Meta-Agent / Orchestrator Advisory (§4)
  │
  ├──► Target Resolver
  ├──► Policy Evaluator (policy-pack component, N4)
  ├──► Rollback Verifier
  └──► [Adapter Hooks]
  │
  ▼
Validation (§5)
  ├──► Static Analysis (required)
  ├──► Provider Dry-Run (Class C+)
  └──► [Extended: Shipyard, TLA+, etc.]
  │
  ▼
Capability Broker (§6)
  │
  ▼
Execution Capsule (§7)
  │
  ▼
External Tool / MCP Server / SaaS Platform
  │
  ▼
Postcondition Monitoring (§9)
  │
  ▼
Audit Ledger (N3 events + N6 evidence bundles)
```

---

## 16. References

- N1 (Core Architecture): Domain model, tool registry schema, agent protocols
- N3 (Event Stream): Audit event schema and emission
- N4 (Policy Packs): Rule schema, enforcement actions, policy evaluation
- N6 (Evidence & Provenance): Evidence bundle structure, artifact provenance
- N11 (reserved): Capability Broker deep-dive (if needed as separate spec)
- N12 (reserved): Fleet Autonomy Governance (enterprise extension)
- I-VALIDATION-STRATEGIES: Extended validation (TLA+, Shipyard, Tonic, canary)
- I-INCIDENT-DIAGNOSTICS: Example incident response workflow pack

---

## 17. Glossary

- **Action Class** — Risk category (A-E) assigned to a tool verb by the tool
  registry; determines verification, validation, and approval requirements
- **Capability** — Ephemeral, scoped authority granted for a specific operation;
  TTL-bounded and revocable
- **Capsule** — Isolated execution environment with restricted filesystem, network,
  and time bounds; destroyed after use
- **Crown Jewel** — Resource whose compromise would cause catastrophic damage;
  requires elevated protection and human approval
- **Execution Capsule** — See Capsule
- **Operational IR** — Verified, compiled representation of an operational intent;
  the canonical plan for execution
- **Operational Intent** — Agent's declaration of desired outcome, target, scope,
  and constraints; compiled into Operational IR
- **Postcondition** — Observable expectation attached to an executed action;
  triggers rollback on failure
- **Trust Level** — Progressive autonomy tier (L0-L4) gating which action classes
  a workflow may execute autonomously
- **Verification Pipeline** — Sequence of checks (target resolution, policy
  evaluation, rollback verification) that an intent must pass before execution

---

**Version History:**

- 0.1.0-draft (2026-03-06): Initial specification consolidating governed execution,
  capability model, execution capsules, validation, crown jewel protection, and
  external system integration
