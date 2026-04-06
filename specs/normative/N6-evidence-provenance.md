<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N6 — Evidence & Provenance Standard

**Version:** 0.5.0-draft
**Date:** 2026-03-08
**Status:** Draft
**Conformance:** MUST

---

## 1. Purpose & Scope

This specification defines the **evidence bundle** and **artifact provenance**
contracts that make autonomous workflows credible to platform and security teams.

**Evidence bundles** provide a complete audit trail from **intent** → **plan** → **implementation** → **validation** →
  **outcome**.

**Artifact provenance** enables tracing any artifact back to its source inputs, tool executions, and semantic intent.

### 1.1 Design Principles

1. **Complete traceability** - Every artifact MUST link back to originating workflow and intent
2. **Semantic validation** - Declared intent MUST be verifiable against actual behavior
3. **Immutable records** - Evidence bundles MUST NOT be modified after creation
4. **Queryable history** - Users MUST be able to query "What was the intent for this artifact?"
5. **Compliance-ready** - Evidence format MUST support SOCII, FedRAMP audit requirements

---

## 2. Evidence Bundle Schema

### 2.1 Evidence Bundle Structure

```clojure
{:evidence-bundle/id uuid
 :evidence-bundle/workflow-id uuid
 :evidence-bundle/created-at inst
 :evidence-bundle/version string

 ;; Original Intent
 :evidence/intent {...}

 ;; Phase Evidence (for each phase executed)
 :evidence/plan {...}
 :evidence/design {...}         ; OPTIONAL: if design phase executed
 :evidence/implement {...}
 :evidence/verify {...}
 :evidence/review {...}

 ;; Validation Evidence
 :evidence/semantic-validation {...}
 :evidence/policy-checks [...]


 ;; Knowledge Inputs and Trust
 :evidence/knowledge-inputs
 [{:knowledge/id uuid
   :knowledge/type keyword                ; :feature-pack | :policy-pack | :agent-profile-pack | :doc
   :knowledge/trust-level keyword         ; :trusted | :untrusted | :tainted
   :knowledge/authority keyword           ; :authority/instruction | :authority/data
   :knowledge/source string               ; path/uri/registry id
   :knowledge/content-hash string         ; sha256
   :knowledge/signature string}]          ; OPTIONAL

 ;; Pack Promotion / Signing (optional)
 :evidence/pack-promotions
 [{:pack/id string
   :pack/type keyword
   :from-trust keyword
   :to-trust keyword
   :promoted-by string
   :promoted-at inst
   :promotion-policy string
   :promotion-justification string       ; REQUIRED: why pack was promoted (e.g., "passed knowledge-safety scans", "manual review approved")
   :pack-hash string
   :pack-signature string}]


 ;; Outcome
 :evidence/outcome {...}

 ;; Compliance Metadata
 :compliance/auditor-notes string   ; OPTIONAL
 :compliance/sensitive-data boolean
 :compliance/pii-handling keyword}  ; :none, :redacted, :encrypted
```

### 2.2 Intent Evidence

```clojure
{:intent/type keyword           ; REQUIRED: :import, :create, :update, :destroy, :refactor
 :intent/description string      ; REQUIRED: Human-readable intent
 :intent/business-reason string  ; REQUIRED: Why this change is needed
 :intent/constraints [...]       ; REQUIRED: Constraints that MUST be satisfied

 :intent/declared-at inst
 :intent/author string}          ; OPTIONAL: User who declared intent
```

#### 2.2.1 Intent Types

Implementations MUST support these intent types:

- `:import` - Import existing resources (no creates/destroys)
- `:create` - Create new resources
- `:update` - Modify existing resources (no creates/destroys)
- `:destroy` - Remove resources
- `:refactor` - Change structure without behavior change (no functional change)
- `:migrate` - Move resources (create + destroy, but logically a move)

#### 2.2.2 Constraints Schema

```clojure
{:constraint/type keyword       ; e.g., :no-resource-creation, :no-downtime
 :constraint/description string
 :constraint/validation-fn ...} ; OPTIONAL: Function to validate constraint
```

### 2.3 Phase Evidence

Each workflow phase MUST produce phase evidence:

```clojure
{:phase/name keyword            ; :plan, :design, :implement, :verify, :review
 :phase/agent keyword           ; Agent that executed phase
 :phase/agent-instance-id uuid

 :phase/started-at inst
 :phase/completed-at inst
 :phase/duration-ms long

 :phase/output {...}             ; Phase-specific output
 :phase/artifacts [uuid ...]     ; Artifacts produced in this phase

 :phase/inner-loop-iterations long  ; OPTIONAL: How many validate/repair cycles
 :phase/event-stream-range {:start-seq long :end-seq long}}  ; Link to events
```

### 2.4 Semantic Validation Evidence

```clojure
{:semantic-validation/declared-intent keyword  ; From intent/type
 :semantic-validation/actual-behavior keyword  ; Inferred from implementation

 :semantic-validation/resource-creates long    ; Count of creates
 :semantic-validation/resource-updates long    ; Count of updates
 :semantic-validation/resource-destroys long   ; Count of destroys

 :semantic-validation/passed? boolean
 :semantic-validation/violations [...]         ; If failed
 :semantic-validation/checked-at inst}
```

#### 2.4.1 Semantic Validation Rules

Implementations MUST validate:

| Intent Type | Allowed Creates | Allowed Updates | Allowed Destroys |
| ----------- | --------------- | --------------- | ---------------- |
| `:import`   | 0               | 0 (state-only)  | 0                |
| `:create`   | >0              | Any             | 0                |
| `:update`   | 0               | >0              | 0                |
| `:destroy`  | 0               | 0               | >0               |
| `:refactor` | 0               | 0               | 0 (code-only)    |
| `:migrate`  | >0              | 0               | >0 (balanced)    |

### 2.5 Policy Check Evidence

```clojure
[{:policy-check/pack-id string      ; e.g., "terraform-aws"
  :policy-check/pack-version string
  :policy-check/phase keyword       ; Phase where check ran
  :policy-check/checked-at inst

  :policy-check/violations [...]    ; See 2.5.1
  :policy-check/passed? boolean
  :policy-check/duration-ms long}]
```

#### 2.5.1 Violation Schema

```clojure
{:violation/rule-id string
 :violation/severity keyword        ; :critical, :high, :medium, :low, :info
 :violation/message string
 :violation/location {...}          ; File, line, etc.
 :violation/remediation string      ; OPTIONAL: How to fix
 :violation/auto-fixable? boolean}
```

### 2.6 Outcome Evidence

```clojure
{:outcome/success boolean
 :outcome/pr-number long           ; OPTIONAL: If PR created
 :outcome/pr-url string            ; OPTIONAL
 :outcome/pr-status keyword        ; OPTIONAL: :open, :merged, :closed
 :outcome/pr-merged-at inst        ; OPTIONAL

 :outcome/error-message string     ; OPTIONAL: If failed
 :outcome/error-phase keyword      ; OPTIONAL: Which phase failed
 :outcome/error-details {...}      ; OPTIONAL
 :outcome/failure-class keyword    ; OPTIONAL: canonical class from N1 §5.3.3

 ;; Reliability measurements (see N1 §5.5)
 :outcome/tier keyword             ; REQUIRED: workflow tier (:best-effort :standard :critical)
 :outcome/degradation-mode keyword ; OPTIONAL: system mode at completion (:nominal :degraded :safe-mode)
 :outcome/sli-measurements         ; OPTIONAL: per-SLI values for this workflow
 [{:sli/name keyword               ; REQUIRED: SLI identifier (N1 §5.5.2)
   :sli/value double               ; REQUIRED: measured value for this workflow
   :sli/target double              ; OPTIONAL: SLO target if applicable
   :sli/met? boolean}]}            ; OPTIONAL: did this workflow meet the SLO?
```

### 2.7 DAG Orchestration Evidence

For DAG-based multi-task execution (see N2 Section 13, I-DAG-ORCHESTRATION), evidence
bundles MUST include DAG and PR lifecycle linkage.

#### 2.7.1 DAG Run Evidence

```clojure
{:evidence/dag-run
 {:dag/id uuid                     ; REQUIRED: DAG definition ID
  :run/id uuid                     ; REQUIRED: Run instance ID
  :run/status keyword              ; :completed, :failed, :partial
  :run/task-count long             ; Total tasks in DAG
  :run/merged-count long           ; Tasks reaching :merged
  :run/failed-count long           ; Tasks reaching :failed
  :run/skipped-count long          ; Tasks skipped due to dependency failure
  :run/metrics {:total-tokens long
                :total-cost-usd decimal
                :total-duration-ms long}
  :run/checkpoint {:ref string}}}  ; OPTIONAL: Resume checkpoint
```

#### 2.7.2 Task Workflow Evidence

Each task in a DAG run MUST produce task workflow evidence:

```clojure
{:evidence/task-workflows
 [{:task/id uuid
   :task/status keyword            ; Terminal status: :merged, :failed, :skipped
   :task/dependencies [uuid ...]   ; IDs of dependency tasks

   ;; PR lifecycle evidence
   :task/pr-lifecycle
   {:pr/id string
    :pr/url string
    :pr/branch string
    :pr/base-sha string
    :pr/head-sha string            ; Final commit SHA
    :pr/opened-at inst
    :pr/merged-at inst             ; OPTIONAL: If merged
    :pr/closed-at inst}            ; OPTIONAL: If closed without merge

   ;; CI evidence
   :task/ci-results
   [{:ci/sha string                ; Commit SHA checked
     :ci/status keyword            ; :success, :failure
     :ci/checks [{:name string :status keyword :duration-ms long}]
     :ci/checked-at inst}]

   ;; Review evidence
   :task/review-results
   [{:review/sha string            ; Commit SHA reviewed
     :review/status keyword        ; :approved, :changes-requested
     :review/approvers [string ...]
     :review/changes-requested-by [string ...]
     :review/reviewed-at inst}]

   ;; Fix iteration evidence
   :task/fix-iterations
   [{:fix/iteration long           ; 1-indexed
     :fix/type keyword             ; :ci-failure, :review-changes, :conflict
     :fix/trigger-sha string       ; SHA that triggered fix
     :fix/result-sha string        ; SHA after fix pushed
     :fix/files-modified [string ...]
     :fix/success? boolean
     :fix/metrics {:tokens long :cost-usd decimal :duration-ms long}
     :fix/attempted-at inst}]

   ;; Task metrics
   :task/metrics
   {:total-attempts long           ; Implementation + fix attempts
    :fix-iterations long           ; Fix loop count
    :ci-retries long               ; CI re-runs
    :tokens long
    :cost-usd decimal
    :duration-ms long}}]}
```

#### 2.7.3 Merge Evidence

For tasks reaching `:merged` terminal state:

```clojure
{:evidence/merge
 {:merge/pr-id string
  :merge/sha string                ; Merge commit SHA
  :merge/method keyword            ; :merge, :squash, :rebase
  :merge/merged-by string          ; User/bot that triggered merge
  :merge/merged-at inst
  :merge/required-approvals-met? boolean
  :merge/ci-green? boolean
  :merge/no-unresolved-threads? boolean
  :merge/branch-up-to-date? boolean}}
```

### 2.8 OPSV Evidence (N7)

For Operational Policy Synthesis workflows (see N7), evidence bundles MUST include:

```clojure
{:evidence/opsv
 {:opsv/experiment-pack-hash string   ; Content hash of Experiment Pack used
  :opsv/experiment-pack-id string
  :opsv/environment-fingerprint       ; Cluster, node pool, image digests, config
  {:cluster string
   :node-pools [string ...]
   :image-digests {...}}

  :opsv/convergence-iterations long   ; Number of convergence iterations
  :opsv/policy-proposals              ; Proposed operational policies
  [{:policy-hash string
    :confidence keyword
    :scaling {...}
    :resources {...}
    :artifact-id uuid}]               ; Link to :operational-policy-proposal artifact

  :opsv/verification
  {:passed? boolean
   :criteria-evaluation [...]         ; Per-criterion results
   :confidence keyword
   :caveats [string ...]}

  :opsv/actuation
  {:mode keyword                      ; :recommend-only, :pr-only, :apply-allowed
   :pr-refs [string ...]              ; PR URLs if PR_ONLY
   :apply-refs [string ...]}          ; Applied resource refs if APPLY_ALLOWED

  :opsv/metric-snapshots [uuid ...]}} ; Links to :opsv-metric-snapshot artifacts
```

### 2.9 Control Action Evidence (N8)

Control actions (see N8) MUST be recorded in evidence bundles:

```clojure
{:evidence/control-actions
 [{:action/id uuid
   :action/type keyword               ; See N8 §3.1
   :action/timestamp inst
   :action/requester {:principal string :listener-id uuid}
   :action/justification string
   :action/approval {:status keyword :approvers [...]}
   :action/result {:status keyword :error {...}}
   :action/pre-state {...}            ; State before action
   :action/post-state {...}}]         ; State after action

 :evidence/annotations
 [{:annotation/id uuid
   :annotation/type keyword           ; :recommendation, :warning, :insight, :question
   :annotation/source {:listener-id uuid :principal string}
   :annotation/target {:workflow-id uuid :event-id uuid}
   :annotation/content {:title string :body string :severity keyword}
   :annotation/timestamp inst}]}
```

### 2.10 External PR Evidence (N9)

External PR evaluations produce evidence using the existing schema. N9 does NOT
define a separate evidence model. Evidence for external PRs uses:

- `:risk-assessment` artifacts with explainable factors (see N9 §5.1)
- `:pr-policy-result` artifacts with per-rule outcomes
- `:pr-readiness-snapshot` artifacts with point-in-time state

All artifacts MUST have `:artifact/content-hash`, `:artifact/provenance`, and
`:artifact/created-at` per §3. Evidence artifacts produced by N9 MUST be immutable
and addressable per §5.1. Policy results and risk factors MUST reference evidence
artifacts, not inline their content.

For external PRs (no workflow), `:provenance/workflow-id` MAY be nil and
`:provenance/phase` SHOULD be `:external-pr-eval`.

### 2.11 Pack Run Evidence

Pack Runs (N1 §2.26) produce evidence using the existing bundle schema with
pack-specific fields.

#### 2.11.1 Pack Run Evidence Requirements

Each Pack Run evidence bundle MUST include:

```clojure
{:evidence/pack-run
 {:pack-run/id uuid
  :pack/id string
  :pack/version string
  :pack/content-hash string            ; Digest at run time
  :pack/publisher string
  :pack/entrypoint string

  :pack/signature-verified? boolean    ; REQUIRED: verification result
  :pack/signature-error string         ; OPTIONAL: error if verification failed

  :pack/capabilities-required
  [{:capability/id string
    :capability/scope keyword}]

  :pack/capabilities-granted
  [{:capability/id string
    :capability/scope keyword
    :capability/granted-by string      ; "user", "policy", "auto"
    :capability/granted-at inst}]

  :pack/capabilities-denied            ; OPTIONAL: capabilities that were denied
  [{:capability/id string
    :capability/scope keyword
    :capability/denied-reason string}]

  :pack/resolved-dependencies          ; REQUIRED if pack has dependencies
  [{:pack/id string
    :pack/version string
    :pack/content-hash string}]

  :pack-run/inputs {...}               ; Input values (redacted per data-handling)
  :pack-run/outputs {...}              ; Output values upon completion
  :pack-run/connector-actions          ; All connector actions taken during run
  [{:action/capability string
    :action/timestamp inst
    :action/result keyword}]}}         ; :success | :failure | :denied
```

#### 2.11.2 Metrics Snapshot Artifact

When a pack performs reporting or analytics, metric queries MUST produce
Metrics Snapshot artifacts:

```clojure
{:artifact/type :metrics-snapshot
 :artifact/content
 {:metrics/query string                ; Query expression
  :metrics/parameters {...}            ; Query parameters
  :metrics/time-window
  {:start inst :end inst}
  :metrics/result-digest string        ; SHA-256 of query results
  :metrics/source string}              ; Metrics endpoint identifier
 :artifact/content-hash string}
```

Metrics Snapshots enable reproducibility by recording the exact query and parameters
used to produce a result, without requiring storage of raw metric data.

#### 2.11.3 Report Artifact

When a pack renders a report, the output MUST be a Report Artifact:

```clojure
{:artifact/type :report-artifact
 :artifact/content
 {:report/template-ref string          ; Template identifier within pack
  :report/template-digest string       ; SHA-256 of template at render time
  :report/input-artifact-refs [uuid ...] ; Input artifacts used
  :report/output-format keyword        ; :markdown | :html | :pdf | :json
  :report/rendered-digest string}      ; SHA-256 of rendered output
 :artifact/content-hash string}
```

Report Artifacts enable provenance tracing from rendered output back to input data
and template.

---

## 3. Artifact Provenance Schema

### 3.1 Artifact Structure

```clojure
{:artifact/id uuid
 :artifact/type keyword           ; See 3.1.1
 :artifact/content ...            ; Type-specific content
 :artifact/content-hash string    ; SHA-256 of content

 :artifact/created-at inst
 :artifact/size-bytes long

 :artifact/provenance {...}       ; See 3.2
 :artifact/metadata {...}}        ; OPTIONAL: Type-specific metadata
```

#### 3.1.1 Artifact Types

Implementations MUST support:

**Core artifact types:**

- `:terraform-plan` - Terraform plan output
- `:terraform-state` - Terraform state file
- `:code-changes` - Code diff or patch
- `:test-results` - Test execution results
- `:review-report` - Code review output
- `:plan-document` - Implementation plan
- `:architecture-diagram` - Design artifacts
- `:evidence-bundle` - Evidence bundle itself (meta)

**Pack artifact types:**

- `:feature-pack` - Normalized feature pack (EDN)
- `:policy-pack` - Policy pack (EDN)
- `:agent-profile-pack` - Agent profile pack (EDN)
- `:pack-index` - Pack manifest (EDN)
- `:etl-report` - ETL run report (classifications, coverage)
- `:risk-report` - Static scanner findings (knowledge-safety)

**OPSV artifact types (N7):**

- `:experiment-pack` - OPSV Experiment Pack definition
- `:operational-policy-proposal` - Proposed operational policy with scaling/sizing config
- `:opsv-verification-report` - Verification pass/fail with per-criterion results
- `:opsv-metric-snapshot` - Metric queries and snapshots used for OPSV conclusions

**Control action artifact types (N8):**

- `:control-action-record` - Audit record of a control action (pre-state, post-state, justification)
- `:annotation-record` - Record of advisory annotations for evidence

**External PR artifact types (N9):**

- `:risk-assessment` - Risk evaluation for a PR with explainable factors (see N9 §5.1)
- `:pr-policy-result` - Policy evaluation result for an external PR
- `:pr-readiness-snapshot` - Point-in-time readiness assessment for a PR

**Evaluation artifact types (N1 §3.3.3):**

- `:golden-set` - Curated workflow inputs paired with known-good outcomes for regression testing
- `:eval-run-result` - Results from golden set evaluation, replay, shadow, or canary execution

```clojure
;; Eval Run Result Artifact
{:artifact/type :eval-run-result
 :artifact/content
 {:eval/mode keyword               ; REQUIRED: :golden-set | :replay | :shadow | :canary
  :eval/golden-set-id string       ; OPTIONAL: if mode is :golden-set
  :eval/source-workflow-id uuid    ; OPTIONAL: if mode is :replay
  :eval/entries
  [{:entry/id string               ; REQUIRED
    :entry/expected map             ; REQUIRED: expected outcome
    :entry/actual map               ; REQUIRED: actual outcome
    :entry/pass? boolean            ; REQUIRED
    :entry/diff map}]              ; OPTIONAL: structured diff
  :eval/pass-rate double           ; REQUIRED: 0.0-1.0
  :eval/evaluated-at inst}}        ; REQUIRED
```

**Pack Run artifact types:**

- `:pack-run-evidence` - Pack Run execution record (see below)
- `:metrics-snapshot` - Query + parameters + time window + result digest
- `:report-artifact` - Rendered report referencing input artifacts and templates by digest

**Data Foundry artifact types (Data Foundry N1–N4):**

- `:dataset` - Versioned tabular dataset with schema, partitioning, and lineage (see Data Foundry N1)
- `:time-series` - Time-indexed dataset with temporal properties and continuity constraints
- `:document-collection` - Semi-structured text document collection (e.g., regulatory filings)
- `:feature-set` - ML feature vectors with labels and training metadata
- `:report` - Aggregated, human-readable analytical output (e.g., risk dashboard, quarterly summary)
- `:connector-state` - Persisted connector cursor and extraction state (see Data Foundry N2)
- `:quality-evaluation` - Immutable record of a quality rule execution against a dataset snapshot (see Data Foundry N4)
- `:lineage-graph` - Directed acyclic graph of dataset dependencies and transformations (see Data Foundry N4)

Artifacts of type `:feature-pack`, `:policy-pack`, `:agent-profile-pack`, and `:workflow-pack` MUST include:

- `:artifact/metadata {:trust-level ... :authority ...}`
- `:artifact/content-hash` computed over canonical EDN
- Optional `:artifact/metadata {:signature ...}` if promoted/signed

### 3.2 Provenance Schema

```clojure
{:provenance/workflow-id uuid
 :provenance/phase keyword
 :provenance/agent keyword
 :provenance/agent-instance-id uuid

 :provenance/created-at inst
 :provenance/created-by-event-id uuid  ; Links to event stream

 :provenance/source-artifacts [uuid ...]  ; Input artifacts
 :provenance/tool-executions [...]        ; Tools used to create artifact

 :provenance/content-hash string          ; Duplicate for quick access
 :provenance/signature string}            ; OPTIONAL: Cryptographic signature
```

#### 3.2.1 Tool Execution Record

```clojure
{:tool/name keyword
 :tool/version string
 :tool/args {...}
 :tool/invoked-at inst
 :tool/duration-ms long
 :tool/exit-code long             ; For command-line tools
 :tool/output-summary string}     ; OPTIONAL: First N lines of output
```

---

## 4. Queryable Provenance

### 4.1 Required Query Operations

Implementations MUST support:

#### 4.1.1 Query Artifact Provenance

```clojure
(query-provenance artifact-store artifact-id)
;; Returns:
{:artifact {...}
 :workflow-id uuid
 :original-intent {...}
 :created-by-phase keyword
 :created-by-agent keyword
 :created-at inst
 :source-artifacts [...]          ; Prior artifacts in chain
 :subsequent-artifacts [...]      ; Artifacts created from this one
 :validation-results [...]        ; Policy checks at this phase
 :full-evidence-bundle {...}}
```

#### 4.1.2 Trace Artifact Chain

```clojure
(trace-artifact-chain artifact-store workflow-id)
;; Returns:
{:intent {...}
 :chain [{:phase :plan
          :agent :planner
          :artifacts [...]
          :timestamp inst}
         {:phase :implement
          :agent :implementer
          :artifacts [...]
          :timestamp inst}
         ...]
 :outcome {...}}
```

#### 4.1.3 Find Intent Mismatches

```clojure
(query-intent-mismatches artifact-store
                         {:time-range [start-inst end-inst]})
;; Returns workflows where declared intent ≠ actual behavior
[{:workflow-id uuid
  :declared-intent :import
  :actual-behavior :create
  :violation-details {...}}]
```

### 4.2 Query API

Implementations MUST provide:

```clojure
;; Get artifact by ID
(get-artifact artifact-store artifact-id)

;; Get all artifacts for workflow
(get-workflow-artifacts artifact-store workflow-id)

;; Get artifacts by phase
(get-artifacts-by-phase artifact-store workflow-id phase)

;; Get artifacts by type
(get-artifacts-by-type artifact-store artifact-type {:limit long :offset long})

;; Get evidence bundle
(get-evidence-bundle artifact-store workflow-id)
```

---

## 5. Evidence Bundle Generation

### 5.1 Bundle Creation Requirements

Implementations MUST:

1. **Create bundle at workflow completion** - Even if workflow fails
2. **Include all phase evidence** - For phases that executed
3. **Link to event stream** - Via event sequence ranges
4. **Generate semantic validation** - Check intent vs. behavior
5. **Include policy check results** - From all gates executed
6. **Calculate content hashes** - For all artifacts
7. **Make bundle immutable** - No modifications after creation

### 5.2 Bundle Storage Requirements

Implementations MUST:

1. **Store bundles durably** - Survive process restarts
2. **Index by workflow ID** - Fast retrieval
3. **Support partial reads** - Don't require loading entire bundle
4. **Compress large bundles** - But maintain queryability
5. **Retain indefinitely** - Unless explicit retention policy

### 5.3 Bundle Format

Implementations SHOULD use EDN (Extensible Data Notation) for:

- Human readability
- Machine parseability
- Clojure native support
- Schema validation

Implementations MAY support JSON for broader tooling compatibility.

---

## 6. Semantic Intent Validation

### 6.1 Validation Process

Implementations MUST:

1. **Extract declared intent** - From workflow spec
2. **Analyze implementation** - Count creates/updates/destroys
3. **Compare against rules** - See 2.4.1
4. **Generate violation report** - If mismatch detected
5. **Block on critical mismatches** - Prevent merge if intent violated

### 6.2 Terraform-Specific Validation

For Terraform workflows, implementations MUST:

1. **Parse terraform plan output** - Extract resource changes
2. **Categorize changes**:
   - Create: `+` or `+/-` (recreate)
   - Update: `~`
   - Destroy: `-` or `-/+` (recreate)
   - Import: `import` blocks (state-only, no `+/-~`)
3. **Count by category** - For semantic validation
4. **Flag dangerous patterns**:
   - Network resource recreates (`-/+` on `aws_route`, `aws_route_table_association`)
   - Database destroys (`-` on `aws_db_instance`)
   - Unintentional state changes (e.g., declared `:import` but sees `+`)

### 6.3 Kubernetes-Specific Validation

For Kubernetes workflows, implementations MUST:

1. **Parse kubectl diff/apply output**
2. **Detect resource lifecycle**:
   - Create: New resources in manifest
   - Update: Changed fields in existing resources
   - Destroy: Resources in cluster but not in manifest
3. **Validate against intent**

---

## 7. Compliance Metadata

### 7.1 Required Metadata

Evidence bundles MUST include:

```clojure
{:compliance/created-at inst
 :compliance/sensitive-data boolean    ; REQUIRED: Was sensitive data detected?
 :compliance/pii-handling keyword      ; REQUIRED: :none, :redacted, :encrypted
 :compliance/retention-policy keyword} ; OPTIONAL: :7-years, :indefinite
```

### 7.2 Sensitive Data Handling

Implementations MUST:

1. **Scan artifacts for sensitive data** - Before storing
2. **Detect patterns**:
   - AWS access keys (20-char uppercase alphanumeric)
   - Passwords in plaintext
   - SSNs, credit cards
3. **Redact or flag** - Replace with `[REDACTED:<type>]` or mark bundle as sensitive
4. **Record in metadata** - Set `compliance/sensitive-data` flag

### 7.3 Audit Trail Requirements

For SOCII/FedRAMP compliance, implementations MUST:

1. **Record all evidence bundle accesses** - Who, when, why
2. **Prevent tampering** - Immutable storage, content hashing
3. **Support export** - Evidence bundles exportable for auditors
4. **Maintain chain of custody** - From intent to outcome

---

## 8. Evidence Bundle Presentation

### 8.1 CLI Evidence View

Implementations MUST provide CLI command:

```bash
miniforge evidence show <workflow-id>
```

Displaying (at minimum):

- Intent summary
- Artifacts per phase
- Policy validation results
- Semantic validation results
- Outcome

### 8.2 TUI Evidence Browser

Implementations MUST provide TUI view with:

1. **Intent panel** - Type, description, constraints
2. **Phase tree** - Expandable phases with artifacts
3. **Validation results** - Policy checks, semantic validation
4. **Artifact viewer** - View artifact content (with syntax highlighting)
5. **Provenance trace** - Navigate artifact chain

### 8.3 Programmatic Access

Implementations MUST provide API:

```clojure
;; Get evidence bundle as data
(evidence/get-bundle artifact-store workflow-id)

;; Export as file
(evidence/export-bundle artifact-store workflow-id output-path)

;; Validate bundle integrity
(evidence/validate-bundle bundle)
```

---

## 9. Conformance & Testing

### 9.1 Evidence Bundle Validation

Implementations MUST validate:

1. **Schema compliance** - All required fields present
2. **Artifact references valid** - All artifact UUIDs exist
3. **Semantic validation correct** - Intent vs. behavior logic correct
4. **Content hashes match** - Artifact content matches hash
5. **Immutability** - Bundle unchanged after creation

### 9.2 Provenance Chain Tests

Conformance tests MUST verify:

1. **Complete traceability** - Can trace from artifact to intent
2. **Chain integrity** - Source artifacts link correctly
3. **Tool execution records** - All tools recorded
4. **Event stream linkage** - Events match phase evidence

### 9.3 Semantic Validation Tests

Conformance tests MUST verify:

1. **Correct violation detection** - `:import` intent with creates flagged
2. **No false positives** - Valid workflows pass validation
3. **Terraform parsing accuracy** - Correctly categorizes all change types
4. **Kubernetes parsing accuracy** - Correctly detects resource changes

---

## 10. Example Evidence Bundle

```clojure
{:evidence-bundle/id #uuid "bundle-123"
 :evidence-bundle/workflow-id #uuid "workflow-abc"
 :evidence-bundle/created-at #inst "2026-01-23T10:30:00Z"
 :evidence-bundle/version "1.0.0"

 ;; Intent
 :evidence/intent
 {:intent/type :import
  :intent/description "Import existing RDS instance to Terraform state"
  :intent/business-reason "Enable infrastructure-as-code management"
  :intent/constraints [{:constraint/type :no-resource-creation}
                       {:constraint/type :no-resource-destruction}]
  :intent/declared-at #inst "2026-01-23T10:00:00Z"
  :intent/author "chris@example.com"}

 ;; Plan Phase
 :evidence/plan
 {:phase/name :plan
  :phase/agent :planner
  :phase/started-at #inst "2026-01-23T10:00:05Z"
  :phase/completed-at #inst "2026-01-23T10:02:30Z"
  :phase/duration-ms 145000
  :phase/artifacts [#uuid "artifact-plan-doc"]
  :phase/event-stream-range {:start-seq 0 :end-seq 15}}

 ;; Implement Phase
 :evidence/implement
 {:phase/name :implement
  :phase/agent :implementer
  :phase/started-at #inst "2026-01-23T10:02:35Z"
  :phase/completed-at #inst "2026-01-23T10:08:10Z"
  :phase/duration-ms 335000
  :phase/artifacts [#uuid "artifact-terraform-plan"
                    #uuid "artifact-code-changes"]
  :phase/inner-loop-iterations 2
  :phase/event-stream-range {:start-seq 16 :end-seq 45}}

 ;; Verify Phase
 :evidence/verify
 {:phase/name :verify
  :phase/agent :tester
  :phase/started-at #inst "2026-01-23T10:08:15Z"
  :phase/completed-at #inst "2026-01-23T10:10:00Z"
  :phase/duration-ms 105000
  :phase/artifacts [#uuid "artifact-test-results"]
  :phase/event-stream-range {:start-seq 46 :end-seq 60}}

 ;; Semantic Validation
 :evidence/semantic-validation
 {:semantic-validation/declared-intent :import
  :semantic-validation/actual-behavior :import
  :semantic-validation/resource-creates 0
  :semantic-validation/resource-updates 0
  :semantic-validation/resource-destroys 0
  :semantic-validation/passed? true
  :semantic-validation/violations []
  :semantic-validation/checked-at #inst "2026-01-23T10:08:12Z"}

 ;; Policy Checks
 :evidence/policy-checks
 [{:policy-check/pack-id "terraform-aws"
   :policy-check/pack-version "1.0.0"
   :policy-check/phase :implement
   :policy-check/violations []
   :policy-check/passed? true
   :policy-check/checked-at #inst "2026-01-23T10:08:08Z"
   :policy-check/duration-ms 2500}]

 ;; Outcome
 :evidence/outcome
 {:outcome/success true
  :outcome/pr-number 234
  :outcome/pr-url "https://github.com/acme/terraform/pull/234"
  :outcome/pr-status :merged
  :outcome/pr-merged-at #inst "2026-01-23T11:00:00Z"}

 ;; Compliance
 :compliance/sensitive-data false
 :compliance/pii-handling :none
 :compliance/retention-policy :7-years}
```

---

## 11. Rationale & Design Notes

### 11.1 Why Evidence Bundles Matter

Evidence bundles make autonomous workflows **credible** because:

1. **Traceability** - "Why did we make this change?" → See original intent
2. **Validation** - "Does implementation match intent?" → Semantic validation proves it
3. **Compliance** - "Can we audit this?" → Complete audit trail
4. **Debugging** - "Why did this fail?" → Phase evidence + event stream
5. **Trust** - "Is it safe to merge?" → Policy checks + semantic validation

### 11.2 Why Semantic Intent Validation

Traditional CI/CD validates "does it work?" (tests, lints). miniforge validates **"does it do what you said it would
do?"**

Example:

- Declared intent: `:import` (no infrastructure changes)
- Actual behavior: Created new security group (`:create`)
- **Violation detected** - Block merge, escalate to human

This catches:

- Accidental drift (meant to import, accidentally created)
- Scope creep (started as import, evolved into create)
- Malicious changes (declared import, hiding a backdoor create)

### 11.3 Why Immutable Evidence

Immutability provides:

- **Compliance** - Can't retroactively change audit trail
- **Debugging** - Evidence doesn't drift over time
- **Trust** - Users know evidence is authoritative

---

## 12. Future Extensions

### 12.1 Cryptographic Signatures (Post-OSS)

Evidence bundles will support:

- Digital signatures by agents
- Signature chains (planner → implementer → reviewer)
- Non-repudiation for compliance

### 12.2 Evidence Comparison (Paid)

Fleet-wide evidence will enable:

- "Show me all IMPORT workflows that created resources" (intent violations)
- "Compare this evidence to similar past workflows"
- "Learning from evidence patterns"

---

## 13. References

- RFC 2119: Key words for use in RFCs to Indicate Requirement Levels
- N2 (Workflow Execution): Workflows produce evidence bundles
- N3 (Event Stream): Evidence bundles link to event streams via sequence ranges
- N4 (Policy Packs): Policy check results stored in evidence
- N7 (Operational Policy Synthesis): OPSV evidence requirements (§2.8)
- N8 (Observability Control Interface): Control action and annotation evidence (§2.9)
- N9 (External PR Integration): External PR evidence artifacts (§2.10, §3.1.1)
- I-DAG-ORCHESTRATION: DAG executor with PR lifecycle evidence requirements

---

**Version History:**

- 0.5.0-draft (2026-03-08): Reliability Nines amendments — Outcome evidence extended with
  SLI measurements, failure class, workflow tier, degradation mode (§2.6); golden-set
  and eval-run-result artifact types (§3.1.1)
- 0.4.0-draft (2026-02-16): Added Pack Run Evidence (§2.11), Metrics Snapshot (§2.11.2),
  Report Artifact (§2.11.3); added pack-run-evidence, metrics-snapshot, report-artifact
  to artifact types (§3.1.1)
- 0.3.0-draft (2026-02-07): Added extension spec evidence from N7, N8, N9
  (§2.8–§2.10, §3.1.1 artifact types)
- 0.2.0-draft (2026-02-03): Add DAG orchestration evidence (Section 2.7: DAG Run, Task Workflow, Merge Evidence)
- 0.1.0-draft (2026-01-23): Initial evidence & provenance specification
