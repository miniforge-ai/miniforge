<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N6 — Evidence & Provenance Standard

**Version:** 0.8.0-draft
**Date:** 2026-08-06
**Status:** Draft
**Conformance:** MUST

_v0.8.0 supplies the sealing mechanism behind the immutability the spec already
claimed (§2.14), the event-stream linkage schema (§2.12), gate-execution
evidence per N4 §5.5 (§2.13), retention (§7.4), and conformance requirement IDs
(§9.4–§9.5); and inherits N3 §8's redaction contract rather than defining a
second marker._

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
5. **Compliance-ready** - Evidence format MUST support SOC 2 and FedRAMP audit requirements

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

 ;; Gate Evidence (N4 §5.5)
 :evidence/gate-executions [...]    ; REQUIRED when any gate ran; see §2.13

 ;; Compliance Metadata
 :compliance/created-at inst        ; REQUIRED
 :compliance/sensitive-data boolean ; REQUIRED
 :compliance/pii-handling keyword   ; REQUIRED: :none, :redacted, :encrypted
 :compliance/retention-policy keyword ; OPTIONAL: see §7.4
 :compliance/auditor-notes string   ; OPTIONAL

 ;; Seal (§2.14)
 :evidence/event-links [...]        ; REQUIRED: one per scope, see §2.12
 :evidence/content-hash string      ; REQUIRED: SHA-256 over the sealed bundle
 :evidence/sealed-at inst           ; REQUIRED
 :evidence/signature string}        ; OPTIONAL
```

The compliance keys here and the required set in §7.1 are one list. A key
required by §7.1 that does not appear in this structure is a defect in this
spec, not a choice for implementations.

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

A phase's typed outcome — including a `:blocked` refusal and its
`:phase/blocked-reason` (RefusalReason) — is carried on the linked
`:workflow/phase-completed` event (N3 §3.1), so refusals are recoverable from
the audit trail via `:phase/event-stream-range` without a separate field here.

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

The workflow MUST allocate the evidence bundle identifier before emitting its
first OPSV event and accumulate material in a run-scoped assembly record. At
terminal disposition it MUST publish one immutable bundle whose identifier is
the preallocated value. Finalization MUST preserve references to every event,
artifact, execution grant, and governed effect accumulated during the run.

```clojure
{:evidence/opsv
 {:opsv/experiment-pack-hash string   ; Content hash of Experiment Pack used
  :opsv/experiment-pack-id string
  :opsv/experiment-pack-artifact-id uuid ; Content-addressed pack artifact
  :opsv/environment-fingerprint       ; Cluster, node pool, image digests, config
  {:cluster string
   :node-pools [string ...]
   :image-digests {...}
   :config-hash string}

  :opsv/event-refs [uuid ...]          ; Complete N3 event identifier set
  :opsv/artifact-refs [uuid ...]       ; Complete referenced artifact set
  :opsv/grant-refs [uuid ...]          ; Complete Ariadne ExecutionGrant identifier set

  :opsv/risk-score
  {:score double                      ; [0.0, 1.0]
   :level keyword                     ; :low, :medium, :high, :critical
   :factors [{:factor keyword
              :input any
              :contribution double
              :rationale string}]}

  :opsv/convergence-iterations long   ; Number of convergence iterations
  :opsv/policy-proposals              ; Proposed operational policies
  [{:policy-hash string
    :confidence keyword
    :scaling {...}
    :resources {...}
    :artifact-id uuid}]               ; Link to :operational-policy-proposal artifact

  :opsv/verification
  {:passed? boolean
   :criteria-evaluation
   [{:criterion/id string
     :criterion/passed? boolean
     :criterion/observed any
     :criterion/expected any
     :criterion/reason-code keyword}]
   :confidence keyword
   :caveats [string ...]}

  :opsv/actuation
  {:requested-actuation-mode keyword  ; :recommend-only, :pr-only, :apply-allowed
   :effective-actuation-mode keyword  ; :none, :recommend-only, :pr-only, :apply-allowed
   :governed-effects                   ; One correlated Ariadne transaction per effect
   [{:evidence/effect-id uuid
     :evidence/grant-id uuid
     :evidence/envelope-id uuid}]
   :pr-refs [string ...]              ; PR URLs if PR_ONLY
   :apply-refs [string ...]           ; Applied resource refs if APPLY_ALLOWED
   :postcondition-artifact-refs [uuid ...]
   :rollback {:status keyword         ; :not-required, :not-triggered, :succeeded, :failed
              :artifact-refs [uuid ...]}}

  :opsv/metric-query-artifact-refs [uuid ...]
  :opsv/metric-snapshot-artifact-refs [uuid ...]
  :opsv/diff-artifact-refs [uuid ...]}}
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

### 2.12 Event Stream Linkage

§5.1 requires a bundle to link to the event stream. `:evidence/event-links` is
a **vector** of links, one per scope covered — never a single map, because a
bundle may span scopes. Each element:

```clojure
{:event-links/scope-type keyword   ; REQUIRED: N3 §2.3 scope — usually :workflow
 :event-links/scope-id any         ; REQUIRED: the scope key's value
 :event-links/from-sequence long   ; REQUIRED: first event covered, inclusive
 :event-links/to-sequence long     ; REQUIRED: last event covered, inclusive
 :event-links/event-count long}    ; REQUIRED: events in range at seal time
```

A sequence range is only meaningful within one N3 scope, because N3 §2.2
sequences per scope. A bundle covering work in a single scope carries a
one-element vector; one spanning scopes carries one element per scope.

Implementations MUST NOT expire an event inside a sealed bundle's range while
the bundle is retained. N3 §4.3.2 forbids expiring from the middle of a
sequence; a bundle whose cited events have been collected cannot be replayed,
which defeats the audit trail it exists to provide.

### 2.13 Gate Execution Evidence

N4 §5.5 requires that a gate result be reproducible from its evidence. For each
gate execution the bundle MUST record:

```clojure
{:gate-execution/gate-id keyword
 :gate-execution/phase keyword
 :gate-execution/outcome keyword          ; :passed | :failed | :waived
 :gate-execution/binding {...}            ; the gate binding per N4 §5.4
 :gate-execution/packs
 [{:pack/id string
   :pack/version string                   ; REQUIRED: exact resolved version
   :pack/content-hash string}]            ; REQUIRED: the bytes that ran
 :gate-execution/violations [...]         ; REQUIRED: all of them, including waived
 :gate-execution/waivers
 [{:waiver/id uuid
   :waiver/evaluation-id uuid
   :waiver/violations [keyword]
   :waiver/actor string
   :waiver/reason string
   :waiver/timestamp inst}]}
```

Three rules follow from N4 §5.5 and are restated because they are the ones
implementations get wrong:

1. A **version range** is not a resolved version. Recording `"^2.0"` does not
   satisfy this section; recording `"2.1.3"` does.
2. Waived violations stay in `:gate-execution/violations`. A waiver records
   that a violation was accepted, never that it was absent.
3. A waiver with no `:waiver/reason` is not a waiver (N4 §6.3.1). A bundle
   carrying one is invalid, not merely incomplete.

### 2.14 Bundle Sealing and Integrity

§1.1 principle 3 requires bundles to be immutable and §9.1 requires
implementations to verify it. This section supplies the mechanism; without one,
"immutable" is an assertion an auditor cannot check.

**Sealing.** At creation, after all evidence is assembled and after scanning and
redaction (§7.2), implementations MUST:

1. Set `:compliance/created-at` and `:evidence/sealed-at`.
2. Compute a SHA-256 over the canonical serialization of the bundle with
   `:evidence/content-hash` and `:evidence/signature` absent, and store it in
   `:evidence/content-hash`.
3. Optionally sign that hash and store the signature in
   `:evidence/signature`.

**Canonical serialization.** The hash MUST be computed over a canonical form:
map keys sorted, no insignificant whitespace, and a stable representation for
each scalar type. Without a canonical form two readers hash the same bundle to
different values and the check is worthless.

**Verification.** `validate-bundle` (§8.3) MUST recompute the hash and compare.
A bundle whose recomputed hash differs from `:evidence/content-hash` MUST be
reported as tampered — not repaired, not re-sealed.

**After sealing** a bundle MUST NOT be modified. Corrections are made by
issuing a new bundle that references the prior one; implementations MUST NOT
edit a sealed bundle in place, including to add auditor notes. Storage SHOULD
enforce this (write-once or equivalent) rather than relying on callers.

An unsealed bundle MUST NOT be exported (§8.3) or presented as evidence.

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
- `:pr-context-pack` - Normalized PR context for consumption by reviewer, meta,
  and governance workflow packs. Content schema:

  ```clojure
  {:artifact/type :pr-context-pack
   :artifact/content
   {:pr/id            uuid                ; PR Work Item id (N9 §2.1)
    :pr/provider      keyword             ; :github | :gitlab | ...
    :pr/repo          string              ; "org/name"
    :pr/number        long
    :pr/diff-summary  {:files-changed long
                       :additions     long
                       :deletions     long
                       :languages     [keyword ...]
                       :paths         [string ...]}
    :pr/metadata      {:author      string
                       :labels      [string ...]
                       :base-branch string
                       :head-branch string
                       :title       string
                       :body        string}
    :pr/ci-status     {:state   keyword   ; :passing | :failing | :pending
                       :checks  [{:name :state :url}]}
    :pr/review-status {:state     keyword ; :approved | :changes-requested | :pending
                       :reviewers [string ...]}
    :pr/readiness     {...}               ; N9 §2.2 readiness snapshot
    :pr/risk          {...}               ; N9 §5 risk assessment summary
    :pr/captured-at   inst}               ; snapshot timestamp
   :artifact/content-hash string}
  ```

  PR Context Packs are emitted by the N9 ingestion pipeline on PR creation
  and on significant updates (see N9 §9.1). They are immutable and
  content-addressable per §5.1; a significant update produces a new
  artifact with a new content-hash, not an in-place mutation.

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

**N3 §8 owns the redaction contract.** Evidence bundles inherit it rather than
defining a second one: the excluded-value set of N3 §8.1, the marker of §8.2,
and the truncation rules of §8.3 apply unchanged to bundle content.

In particular the marker is `"[REDACTED]"`, exactly as on the stream. An
earlier revision of this section specified `[REDACTED:<type>]`; that variant is
withdrawn. Two markers for one concept means an auditor grepping for redactions
finds some of them, and a redaction an auditor cannot find is not a redaction.

Beyond inheriting N3 §8, implementations MUST:

1. **Scan artifacts before storing.** Detection is a bundle-side obligation
   because an artifact may carry a secret that never crossed the event stream.
2. **Detect, at minimum**: cloud provider access keys, plaintext passwords and
   connection strings carrying a secret, private keys, SSNs, and payment card
   numbers.
3. **Redact, then flag.** Redaction is not optional when a secret is found —
   §8.1 of N3 is a MUST NOT, not a preference. Marking the bundle sensitive
   records that a secret was present; it does not license storing it.
4. **Record in metadata.** Set `:compliance/sensitive-data` and, where
   redaction occurred, `:compliance/pii-handling :redacted`.

A bundle MUST NOT be sealed (§2.14) until scanning and redaction have completed.
Sealing a bundle and redacting afterwards would either break the seal or leave
the secret inside a record that claims to be tamper-evident.

### 7.3 Audit Trail Requirements

For SOC 2 / FedRAMP compliance, implementations MUST:

1. **Record all evidence bundle accesses** - Who, when, why
2. **Prevent tampering** - Immutable storage, content hashing
3. **Support export** - Evidence bundles exportable for auditors
4. **Maintain chain of custody** - From intent to outcome

### 7.4 Retention

`:compliance/retention-policy` is optional on a bundle; a retention _floor_ is
not optional on an implementation.

An evidence bundle is the durable record of a workflow, so it inherits the
`:audit` class of N3 §4.3.1: **minimum one year**, or the deployment's stated
policy if longer. Implementations MUST document their actual retention.

Two constraints follow from the rest of this spec:

- A bundle MUST NOT outlive the events it cites (§2.12) — or rather, the events
  MUST NOT be expired first. Retention of a bundle is a floor on the retention
  of its event range.
- Artifacts referenced by a retained bundle MUST be retained with it. A bundle
  whose artifact references dangle fails §9.1's validity check and cannot
  satisfy §7.3's chain of custody.

Deleting a bundle before its floor is a compliance failure, not a storage
optimization. Where regulation requires erasure of specific content, that is
handled by redaction at seal time (§7.2), not by destroying the record.

---

## 8. Evidence Bundle Presentation

**N5 owns the interface surface.** The CLI command, the TUI view, and their
key bindings are specified in N5 §2.3.5 and N5 §3.2.3; this section states what
those surfaces MUST be able to show, not how they look. Restating N5's command
taxonomy here would create a second source of truth that drifts.

### 8.1 Minimum Presented Content

Whatever surface presents a bundle MUST be able to show:

- Intent summary — type, description, constraints
- Artifacts per phase, with provenance navigable from each (§4.1.1)
- Policy validation results, including waived violations marked as waived
- Semantic validation results
- Gate executions with their resolved pack versions and hashes (§2.13)
- Outcome
- Seal status: whether `:evidence/content-hash` verifies (§2.14)

Seal status is not decoration. A presented bundle whose hash does not verify
MUST be shown as tampered rather than rendered as ordinary evidence, on every
surface. Per N5 §6.2 the same applies to waived gates: never rendered as
passing.

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

### 9.4 Conformance Requirements

Requirement IDs are stable identifiers for the normative statements of this
spec, so a conformance suite can cite what it tests. IDs are never reused; a
withdrawn requirement is marked withdrawn, not deleted.

#### Bundle structure and sealing

| ID | Level | Requirement |
|----|-------|-------------|
| N6.EB.1 | MUST | Create a bundle at workflow completion, including for failed and cancelled workflows (§5.1). |
| N6.EB.2 | MUST | Include every §7.1 compliance key, as listed in §2.1 (§2.1, §7.1). |
| N6.EB.3 | MUST | Seal the bundle at creation: set `:evidence/sealed-at` and `:evidence/content-hash` (§2.14). |
| N6.EB.4 | MUST | Compute the seal hash over a canonical serialization, excluding hash and signature (§2.14). |
| N6.EB.5 | MUST NOT | Modify a bundle after sealing, including to add auditor notes (§2.14). |
| N6.EB.6 | MUST | Report a bundle whose recomputed hash differs as tampered, never repair or re-seal it (§2.14, §9.1). |
| N6.EB.7 | MUST NOT | Export or present an unsealed bundle as evidence (§2.14, §8.3). |
| N6.EB.8 | MUST | Seal only after scanning and redaction have completed (§7.2). |

#### Provenance

| ID | Level | Requirement |
|----|-------|-------------|
| N6.PR.1 | MUST | Link every artifact to its originating workflow and intent (§1.1, §3.2). |
| N6.PR.2 | MUST | Record content hashes for all artifacts and verify them on read (§3.1, §9.1). |
| N6.PR.3 | MUST | Support the query operations of §4.1 and the API of §4.2. |
| N6.PR.4 | MUST | Keep artifact references resolvable for as long as the bundle is retained (§7.4). |

#### Event linkage

| ID | Level | Requirement |
|----|-------|-------------|
| N6.EL.1 | MUST | Record `:evidence/event-links` with an N3 scope type, scope id, and sequence range (§2.12). |
| N6.EL.2 | MUST | Record one link per scope when the work spans scopes (§2.12). |
| N6.EL.3 | MUST NOT | Expire an event inside a retained bundle's cited range (§2.12, N3 §4.3.2). |

#### Gate evidence

| ID | Level | Requirement |
|----|-------|-------------|
| N6.GE.1 | MUST | Record the gate binding and resolved rule set for every gate execution (§2.13, N4 §5.5). |
| N6.GE.2 | MUST | Record each pack's exact resolved version — a range does not satisfy this (§2.13). |
| N6.GE.3 | MUST | Record each pack's content hash (§2.13). |
| N6.GE.4 | MUST | Retain waived violations in the violations list, marked waived (§2.13). |
| N6.GE.5 | MUST | Reject a waiver with no `:waiver/reason` as invalid (§2.13, N4 §6.3.1). |

#### Sensitive data and retention

| ID | Level | Requirement |
|----|-------|-------------|
| N6.SD.1 | MUST | Apply N3 §8's excluded-value set, marker, and truncation rules to bundle content (§7.2). |
| N6.SD.2 | MUST | Use the marker `"[REDACTED]"` (§7.2). |
| N6.SD.3 | MUST | Scan artifacts before storing, independently of the event stream (§7.2). |
| N6.SD.4 | MUST | Redact on detection; flagging alone does not satisfy N3 §8.1 (§7.2). |
| N6.SD.5 | MUST | Retain bundles for at least the `:audit` floor of N3 §4.3.1 (§7.4). |

#### Presentation

| ID | Level | Requirement |
|----|-------|-------------|
| N6.PS.1 | MUST | Be able to present every item in §8.1 on whichever surface renders a bundle. |
| N6.PS.2 | MUST | Show a bundle whose seal does not verify as tampered (§8.1). |
| N6.PS.3 | MUST NOT | Render a waived gate as passing (§8.1, N5 §6.2). |

### 9.5 Test Obligations

A conformance suite MUST cover, at minimum:

1. **Seal round-trip** — a sealed bundle verifies; the same bundle
   re-serialized in a different key order still verifies (N6.EB.4).
2. **Tamper detection** — mutating any field of a sealed bundle causes
   verification to fail and the bundle to be reported tampered, not repaired
   (N6.EB.5, N6.EB.6).
3. **Failure and cancellation** — a failed workflow and a cancelled workflow
   each produce a sealed bundle (N6.EB.1).
4. **Gate reproducibility** — the recorded pack versions and hashes are
   sufficient to re-resolve and re-run the gate and obtain the same outcome
   (N6.GE.1–N6.GE.3).
5. **Waiver visibility** — a waived violation appears in the violations list
   marked waived, and no surface renders its gate as passing (N6.GE.4,
   N6.PS.3).
6. **Redaction** — a workflow whose artifact contains a secret produces a
   bundle with `"[REDACTED]"` and no occurrence of the secret, and the bundle
   seals only after that substitution (N6.SD.2, N6.SD.4, N6.EB.8).
7. **Event-link integrity** — every sequence in a bundle's cited range is
   retrievable for as long as the bundle is retained (N6.EL.3).

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
- N3 §2.3 (scopes) and §4.3 (retention) constrain §2.12 and §7.4; N3 §8 owns the
  redaction contract inherited by §7.2
- N4 (Policy Packs): Policy check results stored in evidence; §5.5 imposes the
  gate-evidence obligations of §2.13; §6.3.1 defines the waiver
- N5 (CLI/TUI/API): §2.3.5 and §3.2.3 own the surfaces that present bundles (§8)
- N5-delta-supervisory-control-plane §3.1: Waiver shape recorded in §2.13
- N7 (Operational Policy Synthesis): OPSV evidence requirements (§2.8)
- N8 (Observability Control Interface): Control action and annotation evidence (§2.9)
- N9 (External PR Integration): External PR evidence artifacts (§2.10, §3.1.1)
- I-DAG-ORCHESTRATION: DAG executor with PR lifecycle evidence requirements

---

## Annex A — Implementation Conformance Status (informative)

This annex is **informative**. It records where the miniforge implementation
diverges from the contract above, as of 2026-08-06. It is not a relaxation of
any requirement in §1–§13.

### A.1 Partially Implemented

- **Bundle hash (§2.14).** `evidence-bundle/collector.clj:619` already sets
  `:evidence/content-hash` over the assembled bundle. Until this revision the
  field was not in the spec at all, so the implementation had invented it. What
  is missing is the rest of the mechanism: no canonical serialization is
  specified or implemented, so two readers can hash the same bundle
  differently; there is no `:evidence/sealed-at`; and `validate-bundle` does not
  recompute and compare (N6.EB.3, N6.EB.4, N6.EB.6).

### A.2 Specified, Not Implemented

- **Redaction (§7.2).** `evidence-bundle/scanner.clj` detects email addresses,
  SSNs, and AWS access keys and records findings, but performs no redaction —
  the string `[REDACTED]` appears nowhere in the component. N3 §8.1 is a
  MUST NOT on emission, so detect-and-flag leaves the secret in the bundle
  (N6.SD.4). The detection set is also narrower than §7.2 requires: no private
  keys, no connection strings, no payment card numbers.
- **Gate execution evidence (§2.13).** No waiver, gate binding, or resolved
  pack version is recorded anywhere in the component. All five N6.GE
  requirements are unmet, which means no gate result in the system is currently
  reproducible from its evidence as N4 §5.5 requires.
- **Event stream linkage (§2.12).** No `:evidence/event-links`. §5.1 has always
  required linking to the event stream; nothing implements it.
- **Retention (§7.4).** No retention floor is enforced for bundles or their
  artifacts.

### A.3 Structural

- **Immutability is unenforced.** Nothing prevents a sealed bundle from being
  modified in place; §2.14's write-once expectation has no storage-level
  backing (N6.EB.5).

**Version History:**

- 0.8.0-draft (2026-08-06): Spec-completion pass.
  **New normative sections:** bundle sealing and integrity (§2.14) — the spec
  asserted immutability in §1.1, §7.3, and §9.1 without ever saying how a
  reader verifies it; event stream linkage schema (§2.12), scope-aware per N3
  §2.3; gate execution evidence (§2.13) discharging the four obligations N4
  §5.5 places on this spec — binding, resolved versions, content hashes,
  waivers — none of which the bundle previously recorded; retention (§7.4);
  conformance requirement IDs and test obligations (§9.4–§9.5).
  **Contract fixes:** §7.2 specified the marker `[REDACTED:<type>]` against N3
  §8.2's `[REDACTED]` — withdrawn in favour of inheriting N3 §8 whole, since two
  markers means an auditor grepping for redactions finds only some of them;
  §7.2 also permitted "redact **or** flag" where N3 §8.1 is a MUST NOT;
  §2.1's compliance keys and §7.1's required set disagreed in both directions
  and are now one list; §8.1–§8.2 restated N5's CLI and TUI contracts and now
  reference them, stating only what a surface MUST be able to show; SOCII →
  SOC 2.
  Annex A records implementation divergence.
- 0.7.2-draft (2026-08-06): Replaced stale OPSV capability references with
  Ariadne effect, execution-grant, and decision-envelope correlation
- 0.7.1-draft (2026-08-05): Added aggregate OPSV event, artifact, and capability
  reference fields required by the immutable-finalization contract in §2.8
- 0.7.0-draft (2026-08-04): OPSV evidence now records preallocated bundle
  correlation, content-addressed inputs, explainable risk, per-criterion
  verification, requested/effective actuation, correlated N10 effects,
  postconditions, rollback, and diff/metric artifacts
- 0.6.0-draft (2026-04-23): External-PR artifact amendment — `:pr-context-pack`
  artifact type registered in §3.1.1 with full content schema. PR Context Packs are
  the normalized PR snapshot that reviewer, meta, and governance workflow packs
  consume; registering the artifact type makes the contract portable across packs
  and across N9 ingestion implementations
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
