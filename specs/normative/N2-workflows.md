<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N2 — Workflow Execution Model

**Version:** 0.6.0-draft
**Date:** 2026-08-06
**Status:** Draft
**Conformance:** MUST

---

## 1. Purpose & Scope

This specification defines the **workflow execution model** for miniforge autonomous software factory. It establishes:

- **Phase graph** structure and execution order
- **Phase responsibilities** and expected outputs
- **Inner loop** validation and repair mechanism
- **Unified execution machine** for workflow progression
- **Gate contract** for validation checkpoints
- **Context handoff** protocol between phases

This specification builds upon core concepts defined in N1 (Architecture).

### 1.1 Design Principles

1. **Autonomous progression** - Workflows advance through phases without human intervention (unless gates fail)
2. **Fail-safe** - Validation failures MUST trigger repair, not silent progression
3. **Observable** - All phase transitions MUST emit events (see N3)
4. **Traceable** - Complete evidence trail from intent to outcome (see N6)
5. **Resumable** - Workflows MUST be resumable from an authoritative machine snapshot

---

## 2. Workflow Lifecycle

### 2.1 Authoritative Execution Model

Each workflow run MUST execute against exactly one compiled **execution machine**.

The execution machine is the authoritative source of truth for:

1. Workflow lifecycle status
2. Current phase or non-phase execution state
3. Retry and redirect budgets
4. Resume checkpoints and snapshots
5. Legal next transitions

Implementations MUST NOT split authority between a coarse lifecycle FSM
and separate ad hoc phase-index or redirect logic.

`:workflow/status`, `:workflow/current-phase`, and similar convenience
fields MAY be materialized for UI, telemetry, or APIs, but they MUST be
derived projections of the execution machine state.

### 2.2 Workflow State Projections

The execution machine MUST project at least these workflow lifecycle states:

```clojure
:workflow/status
  :queued       ; Accepted, not yet started
  :running      ; Actively executing a phase or supervisory wait state
  :paused       ; Suspended by human intervention (N8 control action)
  :blocked      ; Cannot proceed — unmet dependency or gate failure
  :completed    ; Workflow completed successfully
  :failed       ; Terminal failure (gate failure, agent error)
  :cancelled    ; Cancelled by human or agent
```

This is the **canonical workflow status vocabulary**. Every other surface
projects onto it and MUST NOT introduce a synonym:

- N5-delta-supervisory-control-plane §3.2's `:workflow-run/status` is this set.
- N5's CLI filters, the TUI, and the API report these values.
- N1 §2's Workflow entity derives `:workflow/status` from this projection.

`:pending` and `:executing` are **withdrawn synonyms**, and they map onto
different canonical states:

| Withdrawn | Canonical | Where it came from |
|-----------|-----------|--------------------|
| `:pending` | `:queued` | earlier revisions of this section |
| `:executing` | `:running` | N5 §2.3.2's CLI filter and the implementation |

Three spellings of two states meant a filter written against one spec silently
matched nothing produced by another.

`:paused` and `:blocked` were previously absent here while N8 defined a pause
control action and the supervisory projection reported both. A state an
operator can put a workflow into MUST exist in the authority that defines the
lifecycle.

The machine MAY use more specific internal state identifiers such as `:phase/plan`,
`:phase/verify`, `:awaiting-operator`, or `:releasing`, but those states MUST map
deterministically onto the lifecycle projection above.

**Terminal states** are `:completed`, `:failed`, and `:cancelled`. They MUST NOT
transition back to an active state. Re-running work that reached a terminal
state MUST produce a new workflow run with a new `:workflow/id` (§8.1).

### 2.3 State Transition Diagram

```text
queued ──[start]──► running ──[complete machine]──► completed
                      │  ▲
        [pause]───────┤  │
                      ▼  │
                   paused┘
                      │  ▲
     [dependency or ──┤  │
      gate blocks]    ▼  │
                  blocked┘
                      │
                      │ [terminal failure]
                      ▼
                    failed

  running | paused | blocked ──[cancel action]──► cancelled
```

`:paused` and `:blocked` are non-terminal: both return to `:running`. The
difference is who clears them — `:paused` by an operator resuming, `:blocked`
by the blocking condition being satisfied.

Within `:running`, the execution machine MAY move through workflow-defined states,
including phase states, retry states, review or release states, and temporary
awaiting-supervision states, provided those transitions are encoded in the same
authoritative machine.

### 2.4 Workflow Lifecycle Events

Implementations MUST emit these events (see N3):

1. **workflow/started** - When workflow begins execution
2. **workflow/phase-started** - When each phase begins
3. **workflow/phase-completed** - When each phase completes
4. **workflow/completed** - When entire workflow succeeds
5. **workflow/failed** - When workflow fails
6. **workflow/cancelled** - When the workflow is cancelled

Implementations MUST also emit the checkpoint and resume family of N3 §3.21 —
`workflow/checkpoint-written`, `workflow/checkpoint-write-failed`,
`workflow/machine-snapshot-written`,
`workflow/machine-snapshot-write-failed`, `workflow/resumed`, and
`workflow/spec-hash-mismatch` — at the points N2-delta-phase-checkpoint-and-resume
§9 defines. §8 of this spec depends on those writes having happened; a resume
protocol whose checkpoint writes are unobservable cannot be audited when it
fails.

A transition into `:paused` or `:blocked` MUST be observable. Where the
transition results from an N8 control action, the `control-action/executed`
event of N3 §3.15 carries it; where it results from a gate or dependency, the
corresponding `gate/failed` or task event does.

---

## 3. Workflow Graph and Phase Model

### 3.1 Workflow Definition Model

A workflow definition is a graph that is compiled into the per-run execution machine.

Implementations MUST support workflow definitions that declare:

1. Workflow family (for example `:software-factory` or `:etl`)
2. Workflow profile (for example `:canonical-sdlc`, `:quick-fix`, `:review-first`, `:financial-etl`)
3. Nodes or states
4. Transitions and terminal states
5. Retry, rollback, or redirect budgets
6. Optional skip or policy-gated edges

### 3.1.1 Canonical SDLC Profile

```text
Plan → Design → Implement → Verify → Review → Release → Observe
```

The sequence above defines the **canonical SDLC** profile for software-factory workflows.
It is not the only valid workflow shape. ETL workflows and future SDLC paradigms MAY
compile different graphs so long as they satisfy this specification's machine,
gate, evidence, and observability requirements.

### 3.1.2 Workflow Family and Profile Selection

Implementations MUST separate **workflow selection** from **workflow execution**:

1. Selection chooses a workflow family, profile, and policy set for the run
2. Execution runs the compiled machine for that chosen definition

The selected definition MAY be static, policy-driven, or later learned by convergence logic,
but once execution begins, the run MUST have one authoritative compiled machine snapshot.
Mid-run re-planning MAY be supported only as an explicit machine transition or intervention,
not by mutating the workflow graph out-of-band.

### 3.1.3 Phase Outcome Semantics

Phases MUST NOT imperatively choose the next phase by mutating execution indexes,
redirect targets, or other control-flow fields.

Instead, a phase MUST emit one or more outcome events, such as:

- `:phase/succeeded`
- `:phase/failed`
- `:phase/retry-requested`
- `:phase/rollback-requested`
- `:phase/escalated`

The compiled execution machine MUST determine the legal next transition from those events,
its current state, guards, and configured budgets.

### 3.2 Phase Definitions

#### 3.2.1 Plan Phase

**Purpose:** Decompose intent into actionable tasks

**Primary Agent:** Planner

**Inputs:**

- Workflow specification
- Intent declaration
- Relevant patterns from knowledge base

**Outputs:**

- Implementation plan with tasks
- Risk assessment
- Success criteria

**Artifacts:**

- Plan document (`:plan-document`)

**Gates:** None (planning cannot "fail", only produce poor plans)

**Requirements:**

Implementations MUST:

1. Analyze workflow spec and intent
2. Decompose into concrete tasks
3. Identify risks and constraints
4. Query knowledge base for similar past workflows
5. Produce structured plan

```clojure
;; Plan output schema
{:plan/id uuid
 :plan/approach string           ; High-level approach
 :plan/tasks
 [{:task/id string
   :task/type keyword            ; :implement, :test, :review
   :task/description string
   :task/constraints [...]       ; Constraints from intent
   :task/estimated-complexity keyword}] ; :low, :medium, :high

 :plan/risks
 [{:risk/description string
   :risk/severity keyword        ; :low, :medium, :high, :critical
   :risk/mitigation string}]

 :plan/success-criteria [string ...]}
```

#### 3.2.2 Design Phase (OPTIONAL)

**Purpose:** Create architecture or approach for complex changes

**Primary Agent:** Designer

**Inputs:**

- Plan from Plan phase
- Workflow spec
- Existing architecture (if available)

**Outputs:**

- Architecture decision record (ADR)
- Files to modify/create
- Component interactions

**Artifacts:**

- Architecture diagram (`:architecture-diagram`)
- ADR document (`:adr-document`)

**Gates:** Architecture review (OPTIONAL, if configured)

**Requirements:**

Implementations SHOULD skip Design phase if:

- Change is low complexity (per Planner assessment)
- No architectural decisions required

Implementations MUST execute Design phase if:

- Change affects multiple components
- Architectural decision required
- User explicitly requests design phase

```clojure
;; Design output schema
{:design/id uuid
 :design/approach string
 :design/files-to-change [string ...]
 :design/files-to-create [string ...]
 :design/architecture-decisions
 [{:decision/id string
   :decision/question string
   :decision/options [string ...]
   :decision/chosen string
   :decision/rationale string}]

 :design/component-interactions [...]}
```

#### 3.2.3 Implement Phase

**Purpose:** Write code, configuration, or infrastructure

**Primary Agent:** Implementer

**Inputs:**

- Plan from Plan phase
- Design from Design phase (if executed)
- Intent and constraints from workflow spec
- Active policy packs

**Outputs:**

- Code changes (diff or patch)
- Modified files
- Implementation notes

**Artifacts:**

- Code changes (`:code-changes`)
- Terraform plan (`:terraform-plan`) - if Terraform workflow
- Kubernetes manifests (`:k8s-manifests`) - if K8s workflow

**Gates:**

- Policy validation (REQUIRED)
- Semantic intent validation (REQUIRED)
- Lint checks (OPTIONAL)

**Requirements:**

Implementations MUST:

1. Generate code/config following plan
2. Respect all constraints from intent
3. Run inner loop validation (see Section 5)
4. Pass all gates before completing phase

```clojure
;; Implement output schema
{:implement/id uuid
 :implement/files-changed
 [{:file/path string
   :file/content-before string   ; OPTIONAL: if modification
   :file/content-after string
   :file/diff string}]

 :implement/implementation-notes string
 :implement/approach-used string
 :implement/inner-loop-iterations long}
```

#### 3.2.4 Verify Phase

**Purpose:** Test and validate implementation

**Primary Agent:** Tester

**Inputs:**

- Implementation artifacts
- Plan success criteria
- Intent constraints

**Outputs:**

- Test results
- Validation status
- Issues found (if any)

**Artifacts:**

- Test results (`:test-results`)
- Validation report (`:validation-report`)

**Gates:**

- Test pass/fail (REQUIRED)
- Coverage thresholds (OPTIONAL)

**Requirements:**

Implementations MUST:

1. Run existing test suites (if applicable)
2. Validate constraints from intent are met
3. Check success criteria from plan
4. Report test failures with details

```clojure
;; Verify output schema
{:verify/id uuid
 :verify/tests-run long
 :verify/tests-passed long
 :verify/tests-failed long
 :verify/test-results
 [{:test/name string
   :test/status keyword          ; :passed, :failed, :skipped
   :test/duration-ms long
   :test/error string}]          ; OPTIONAL: if failed

 :verify/constraints-validated
 [{:constraint/type keyword
   :constraint/satisfied? boolean
   :constraint/evidence string}]

 :verify/passed? boolean}
```

#### 3.2.5 Review Phase

**Purpose:** Review code quality, policy compliance, semantic intent

**Primary Agent:** Reviewer

**Inputs:**

- Implementation artifacts
- Verification results
- Intent declaration
- Active policy packs

**Outputs:**

- Review report
- Policy violations (if any)
- Semantic intent validation results

**Artifacts:**

- Review report (`:review-report`)
- Policy check results (`:policy-results`)

**Gates:**

- Review approval (REQUIRED if violations found)
- Semantic intent match (REQUIRED)

**Requirements:**

Implementations MUST:

1. Check semantic intent vs. actual behavior (see Section 6)
2. Run all active policy packs
3. Validate code quality
4. Flag violations with remediation guidance

```clojure
;; Review output schema
{:review/id uuid
 :review/policy-checks
 [{:policy-pack/id string
   :policy-pack/version string
   :policy-check/violations [...]  ; See N4
   :policy-check/passed? boolean}]

 :review/semantic-validation
 {:declared-intent keyword
  :actual-behavior keyword
  :passed? boolean
  :violations [...]}                ; If mismatch

 :review/code-quality
 {:quality-score long              ; OPTIONAL: 0-100
  :issues [...]}                    ; OPTIONAL

 :review/approved? boolean}
```

#### 3.2.6 Release Phase (OPTIONAL)

**Purpose:** Deploy to production or create PR

**Primary Agent:** Releaser

**Inputs:**

- Approved implementation
- Evidence bundle
- Deployment configuration

**Outputs:**

- PR number and URL (if creating PR)
- Deployment status (if deploying)

**Artifacts:**

- PR metadata (`:pr-metadata`)
- Deployment manifest (`:deployment-manifest`)

**Gates:**

- Deployment validation (REQUIRED if deploying to production)

**Requirements:**

Implementations MUST:

1. Create PR with complete evidence bundle
2. Link PR to workflow for traceability
3. Include semantic validation results in PR description

Implementations MAY:

- Automatically merge PR if all gates passed and policy allows
- Deploy to production if configured

```clojure
;; Release output schema
{:release/id uuid
 :release/pr-number long
 :release/pr-url string
 :release/pr-branch string
 :release/pr-created-at inst

 :release/pr-description string   ; Includes evidence summary
 :release/evidence-bundle-id uuid

 :release/deployment-status keyword} ; OPTIONAL: :pending, :deployed, :failed
```

#### 3.2.7 Observe Phase

**Purpose:** Capture learnings from workflow execution

**Primary Agent:** Observer

**Inputs:**

- Complete workflow execution
- Evidence bundle
- Event stream

**Outputs:**

- Learning signals
- Patterns extracted
- Heuristic improvement proposals

**Artifacts:**

- Learning notes (`:learning-notes`)
- Pattern records (`:pattern-records`)

**Gates:** None

**Requirements:**

Implementations MUST:

1. Extract signals from workflow execution (duration, iterations, outcomes)
2. Update knowledge base with learnings
3. Propose heuristic improvements (if patterns detected)
4. Record signals for the learning layer and meta loop (see N1, Section 3.3)

The Observe phase remains part of the per-run execution machine. The cross-run learning
meta loop consumes Observe outputs and other evidence after or alongside workflow execution,
but it is not itself the live supervisory mechanism for phase transitions.

```clojure
;; Observe output schema
{:observe/id uuid
 :observe/signals
 [{:signal/type keyword           ; :performance, :quality, :outcome
   :signal/metric keyword
   :signal/value ...
   :signal/context {...}}]

 :observe/patterns-extracted
 [{:pattern/type keyword
   :pattern/description string
   :pattern/frequency long
   :pattern/confidence float}]

 :observe/heuristic-proposals
 [{:heuristic/target string       ; Which prompt to improve
   :heuristic/improvement string
   :heuristic/rationale string}]

 :observe/knowledge-base-updates [uuid ...]}
```

---

## 4. Phase Execution Requirements

### 4.1 Phase Prerequisites

Before executing a phase, implementations MUST verify:

1. **Prior phases completed** - All prerequisite phases have status `:completed`
2. **Context available** - Input context from prior phases is present
3. **Gates passed** - All gates from prior phases passed
4. **Agent available** - Required agent is initialized and ready

### 4.2 Phase Execution Steps

For each phase, implementations MUST:

1. **Confirm the current machine state** permits entering that phase
2. **Emit phase-started event** (see N3)
3. **Initialize agent** with context
4. **Execute agent** (invoke method)
5. **Run inner loop** validation and repair (see Section 5)
6. **Execute gates** (see Section 6)
7. **Emit phase outcome event(s)** for the execution machine
8. **Persist machine snapshot and artifacts** with provenance (see N6)
9. **Update derived phase context** for downstream consumers
10. **Emit phase-completed or phase-failed event** (see N3)

### 4.3 Phase Failure Handling

If a phase fails, implementations MUST:

1. **Emit phase-failed and machine-transition events** with reason
2. **Record partial evidence** (even for incomplete phase)
3. **Apply the failure transition defined by the execution machine**
4. **Notify the supervisory machine** so live governance state reflects the failure
5. **Escalate to human** with failure details and remediation options when machine policy requires it

Options for user:

- Retry phase (with modified context)
- Skip phase (if safe)
- Cancel workflow
- Override gate (if permitted by policy)

Human or automated intervention MUST flow through bounded supervisory control actions.
The workflow runner MUST NOT accept direct phase-index manipulation or uncontrolled
state mutation as a substitute for those actions.

### 4.4 Phase Context Handoff

Each phase MUST receive a complete context:

```clojure
{:workflow/id uuid
 :workflow/spec {...}              ; Original specification
 :workflow/intent {...}            ; Intent declaration

 :execution/machine-state keyword  ; Current authoritative execution state
 :execution/machine-snapshot {...} ; Durable snapshot for resume and supervision

 :phase/name keyword               ; Current phase
 :phase/input-context
 {:prior-phases                    ; Map of completed phases
  {:plan {:plan/tasks [...] ...}
   :design {:design/files-to-change [...] ...}
   :implement {:implement/files-changed [...] ...}}

  :knowledge/patterns [...]        ; Relevant patterns from knowledge base
  :policy/active-packs [...]       ; Policy packs to enforce
  :artifacts/available [uuid ...]} ; Artifacts from prior phases

 :phase/constraints [...]          ; Constraints to satisfy
 :phase/success-criteria [...]}    ; Success criteria to meet
```

#### 4.4.1 Instruction vs Data Channels (Cognitive Safety Boundary)

Phase context MUST explicitly separate **instruction authority** from **untrusted data**.

- `:instructions` are platform-authored or policy-approved and MAY shape plans and execution.
- `:data` is repo- or user-derived content and MUST be treated as reference material only.

Implementations MUST ensure that untrusted documents (e.g., markdown specs, READMEs,
`agents.md`) are never elevated into instruction authority unless first normalized into
schema-valid packs and promoted to `:trusted` under policy.

Recommended context shape:

```clojure
:phase/input-context
{:instructions
 {:policy/active-packs [...]
  :constraints [...]
  :success-criteria [...]}

 :data
 {:prior-phases {...}
  :knowledge/patterns [...]          ; Trusted patterns only
  :knowledge/inputs [...]            ; All knowledge inputs with trust labels + hashes
  :repo/docs [...]                   ; Untrusted docs (data channel)
  :artifacts/available [uuid ...]}

 :data/trust-verified                ; OPTIONAL: scanner-verified but not promoted content
 {:repo/specs [...]                  ; Specs that passed safety scanning
  :repo/docs [...]                   ; Docs that passed injection scanning
  :scanner-findings [...]}}          ; Associated scanner findings for transparency
```

The `:data/trust-verified` subchannel MAY be used to provide richer context for agent
reasoning without elevating content to instruction authority. Content in this channel
MUST have passed deterministic safety scanners (see N4 "knowledge-safety") but retains
`:untrusted` trust level.

Each phase MUST produce a next context:

```clojure
{:phase/completed true
 :phase/output {...}               ; Phase-specific output
 :phase/artifacts [uuid ...]       ; Artifacts produced
 :phase/metadata {...}}            ; Any additional context for next phase
```

---

## 5. Inner Loop: Validate → Repair

### 5.1 Inner Loop Purpose

The **inner loop** validates agent output and repairs it if validation fails, without human intervention.

### 5.2 Inner Loop Flow

```text
1. Agent generates output
     ↓
2. Validate output against constraints
     ↓
3. Passed? → Yes → Complete phase
     ↓ No
4. Attempt repair
     ↓
5. Increment iteration count
     ↓
6. Exceeded retry budget? → Yes → Escalate to human
     ↓ No
7. Go to step 2 (re-validate)
```

### 5.3 Inner Loop Requirements

Implementations MUST:

1. **Validate before completion** - Never complete phase without validation
2. **Limit iterations** - Enforce retry budget (RECOMMENDED: 3-5 iterations)
3. **Record iterations** - Store iteration count in phase evidence
4. **Try multiple strategies** - Don't repeat same repair approach
5. **Escalate exhausted budget** - Prompt human if all repair attempts fail

### 5.4 Validation Criteria

Inner loop validation MUST check:

1. **Constraint satisfaction** - All constraints from intent satisfied?
2. **Policy compliance** - All policy checks passing?
3. **Semantic intent** - Declared intent matches actual behavior?
4. **Syntax correctness** - Code/config syntactically valid?

### 5.5 Repair Strategies

Implementations SHOULD try these repair strategies in order:

#### 5.5.1 Direct Fix

Agent repairs its own output based on validation feedback.

```clojure
;; Validation result provides feedback
{:validation/passed? false
 :validation/violations
 [{:violation/rule "no-resource-creation"
   :violation/message "Found 3 resource creates, but intent is IMPORT"
   :violation/location "main.tf:45-47"}]
 :validation/remediation
 "Use 'import' blocks instead of resource definitions"}

;; Agent regenerates with remediation guidance
```

#### 5.5.2 Peer Agent Consultation

Agent asks another agent for help.

```clojure
;; Implementer asks Planner for clarification
{:message/type :clarification-request
 :message/from :implementer
 :message/to :planner
 :message/content "Should RDS import create new security group or reuse existing?"
 :message/context {:violation "no-resource-creation"}}

;; Planner responds
{:message/type :clarification-response
 :message/from :planner
 :message/to :implementer
 :message/content "Reuse existing security group 'sg-prod-rds'"
 :message/rationale "IMPORT intent means no new resources"}
```

#### 5.5.3 Knowledge Base Lookup

Agent searches knowledge base for similar past failures and repairs.

```clojure
;; Query knowledge base
(knowledge/find-similar kb
  {:pattern-type :validation-failure
   :violation-type :no-resource-creation
   :workflow-type :terraform-import})

;; Returns past successful repairs
[{:pattern/id uuid
  :pattern/violation "Found creates during IMPORT"
  :pattern/repair "Used 'import' blocks with 'to' attribute"
  :pattern/success-rate 0.95}]
```

#### 5.5.4 Human Escalation

If all strategies fail, escalate to human with:

- Validation failures
- Repair attempts made
- Suggested next steps

### 5.6 Inner Loop Example

```clojure
;; Iteration 1: Generate
(def output-v1 (agent/invoke implementer context))
;; Validation: FAIL - Found resource creates
;; Repair strategy: Direct fix with remediation guidance

;; Iteration 2: Repair
(def output-v2 (agent/repair implementer output-v1 validation-result))
;; Validation: FAIL - Still has creates (in different location)
;; Repair strategy: Consult Planner

;; Iteration 3: Repair with Planner guidance
(def clarification (ask-agent :implementer :planner "Should we...?"))
(def output-v3 (agent/repair implementer output-v2 clarification))
;; Validation: PASS
;; Complete phase
```

---

## 6. Gate Execution Contract

### 6.1 Gate Definition

A **gate** is a validation checkpoint that artifacts must pass before phase completion.

### 6.2 Gate Types

Implementations MUST support:

| Gate Type                 | Purpose                        | When Executed            |
| ------------------------- | ------------------------------ | ------------------------ |
| **Policy Validation**     | Check policy pack rules        | Implement, Review phases |
| **Semantic Intent**       | Verify intent matches behavior | Review phase             |
| **Test Pass/Fail**        | Ensure tests pass              | Verify phase             |
| **Review Approval**       | Human or automated review      | Review phase             |
| **Deployment Validation** | Validate deployment safety     | Release phase            |

### 6.3 Gate Execution Protocol

All gates MUST implement:

```clojure
(defprotocol Gate
  (check [gate artifacts context]
    "Validate artifacts against gate criteria.
     Returns {:passed? boolean :violations [...] :remediation {...}}")

  (repair [gate artifacts violations context]
    "Attempt to repair violations (if gate supports auto-repair).
     Returns {:repaired? boolean :artifacts [...]}"))
```

### 6.4 Gate Contract Schema

```clojure
{:gate/id keyword
 :gate/type keyword                ; :policy, :semantic-intent, :test, :review, :deployment
 :gate/phase keyword               ; Which phase this gate applies to

 :gate/artifacts [uuid ...]        ; Artifacts to validate

 :gate/status keyword              ; :pending, :checking, :passed, :failed
 :gate/passed? boolean

 :gate/violations [...]            ; If failed (see Section 6.5)
 :gate/remediation {...}           ; How to fix violations

 :gate/auto-repair? boolean        ; Can gate attempt auto-repair?
 :gate/allow-override? boolean     ; Can human override failure?

 :gate/started-at inst
 :gate/completed-at inst
 :gate/duration-ms long}
```

### 6.5 Violation Schema

```clojure
{:violation/id uuid
 :violation/gate-id keyword
 :violation/rule-id string         ; Policy rule or validation rule

 :violation/severity keyword       ; :critical, :high, :medium, :low, :info
 :violation/message string         ; Human-readable violation description
 :violation/location {...}         ; File, line, resource, etc.

 :violation/auto-fixable? boolean
 :violation/remediation string}    ; How to fix
```

### 6.6 Gate Enforcement Requirements

Implementations MUST:

1. **Execute gates before phase completion** - Don't complete phase until gates pass
2. **Record gate results in evidence** - Store in evidence bundle (see N6)
3. **Block on critical violations** - MUST NOT proceed if critical violations found
4. **Attempt auto-repair** - If gate supports it and violations are auto-fixable
5. **Escalate gate failures** - Prompt human if gate cannot auto-repair

### 6.7 Semantic Intent Gate

The **semantic intent gate** validates that actual behavior matches declared intent.

#### 6.7.1 Semantic Validation Rules

| Intent Type | Validation Rule                              |
| ----------- | -------------------------------------------- |
| `:import`   | 0 creates, 0 updates, 0 destroys             |
| `:create`   | >0 creates, 0 destroys                       |
| `:update`   | 0 creates, >0 updates, 0 destroys            |
| `:destroy`  | 0 creates, 0 updates, >0 destroys            |
| `:refactor` | Code changes only, 0 resource changes        |
| `:migrate`  | Balanced creates and destroys (logical move) |

#### 6.7.2 Terraform-Specific Semantic Validation

For Terraform workflows, implementations MUST:

1. **Parse terraform plan output**
2. **Categorize changes**:
   - Create: `+` or `+/-` (recreate)
   - Update: `~`
   - Destroy: `-` or `-/+` (recreate)
   - Import: `import` blocks (state-only)
3. **Count by category**
4. **Compare against declared intent**
5. **Flag violations**

Example:

```clojure
;; Declared intent
{:intent/type :import}

;; Terraform plan analysis
{:terraform-plan/creates 3      ; ❌ VIOLATION
 :terraform-plan/updates 0
 :terraform-plan/destroys 0
 :terraform-plan/imports 1}

;; Semantic validation result
{:semantic-validation/passed? false
 :semantic-validation/violations
 [{:violation/message "Intent is IMPORT but found 3 resource creates"
   :violation/severity :critical
   :violation/remediation "Remove 'resource' blocks, use 'import' blocks only"}]}
```

---

## 7. Phase Skipping & Conditional Execution

### 7.1 Optional Phases

The following phases MAY be skipped:

- **Design** - Skip if change is low complexity
- **Release** - Skip if workflow is exploratory (no deployment)

### 7.2 Skipping Criteria

Implementations MAY skip Design phase if:

1. Planner assesses complexity as `:low`
2. No architectural decisions required
3. Single file change

Implementations MUST NOT skip:

- Plan phase (required to understand intent)
- Implement phase (required to generate code)
- Verify phase (required to validate correctness)
- Review phase (required for policy enforcement)

### 7.3 Phase Skipping Event

If phase skipped, implementations MUST emit:

```clojure
{:event/type :workflow/phase-skipped
 :workflow/id uuid
 :workflow/phase :design
 :reason "Low complexity, no architectural decisions required"}
```

---

## 8. Workflow Resumption

### 8.1 Resume Requirements

Implementations MUST support resuming a workflow from an authoritative machine
snapshot when the run has not reached a terminal state (§2.2) — that is, when
it is `:running`, `:paused`, or `:blocked` and execution was interrupted:

- Process crashed and was restarted
- Transient error interrupted execution (LLM timeout, network issue)
- The run was paused by an operator and is being resumed

Resume continues an existing run. It MUST NOT be used to reactivate a terminal
run: `:completed`, `:failed`, and `:cancelled` never return to an active state
(§2.2). Re-running that work is a **new** workflow run with a new
`:workflow/id`, which MAY seed itself from the prior run's artifacts.

An earlier revision listed "user cancelled workflow and wants to restart" as a
resume case. That is withdrawn — it contradicted terminality here and in
N5-delta-supervisory-control-plane §3.2, and it would have made a cancelled
workflow's evidence bundle (N6) describe a run that later continued.

### 8.2 Resume Protocol

To resume workflow:

1. **Load the most recent machine snapshot** from persistent storage
2. **Verify the snapshot and workflow definition are compatible** by comparing
   the recorded spec hash against the current spec. On mismatch, emit
   `workflow/spec-hash-mismatch` (N3 §3.21) and apply
   N2-delta-phase-checkpoint-and-resume §4's disposition
3. **Restore machine state, machine context, and completed phase artifacts**
4. **Rebuild derived projections** such as `:workflow/status` and `:workflow/current-phase`
5. **Resume execution** by dispatching the next legal machine event
6. **Emit `workflow/resumed`** (N3 §3.21) recording the state and phase resumed
   from and the phases skipped as already complete

The resumed run continues the original run's identity and event sequence:
`:workflow/id` is unchanged, sequence numbers do not reset, and
`workflow/started` MUST NOT be re-emitted (N3 §3.21).

```clojure
;; Resume workflow
(workflow/resume engine workflow-id)
;; → Restores the execution machine and continues from its next legal transition
```

### 8.3 Resume Constraints

Implementations MUST NOT resume if:

- The run is in a terminal state — `:completed`, `:failed`, or `:cancelled` (§2.2)
- The machine snapshot fails its integrity check, or the workflow state is
  otherwise unreadable
- The snapshot is **stale** per §8.4

### 8.4 Snapshot Staleness

"Too much time has passed" is not a contract an implementation can apply
consistently, so this section replaces it.

A snapshot is stale when any of the following holds:

1. Its age exceeds the deployment's configured resume window. Implementations
   MUST make this window configurable and MUST document the default.
2. The workflow spec hash has changed and
   N2-delta-phase-checkpoint-and-resume §4 dispositions the mismatch as
   non-resumable.
3. External state the snapshot depends on can no longer be reached — a
   worktree, a container, or a checked-out revision that no longer exists.

Staleness is a refusal, not a silent restart. An implementation that declines
to resume MUST report which of the three conditions applied, and MUST leave the
run in its existing non-terminal state rather than marking it failed on the
operator's behalf.

---

## 9. Workflow Specification Schema

### 9.1 Workflow Spec Structure

```clojure
{:workflow/type keyword            ; REQUIRED: :infrastructure-change, :feature, :refactor, :etl
 :workflow/tier keyword            ; OPTIONAL: :best-effort | :standard | :critical
                                   ;           default: :standard (see N1 §5.5.1)

 :workflow/intent
 {:intent/type keyword             ; REQUIRED: :import, :create, :update, :destroy, :refactor, :migrate
  :intent/description string        ; REQUIRED: What this workflow does
  :intent/business-reason string    ; REQUIRED: Why this change is needed
  :intent/constraints [...]         ; REQUIRED: Constraints to satisfy
  :intent/author string}            ; OPTIONAL: Who requested this

 :workflow/target
 {:repo/url string                 ; REQUIRED: Target repository
  :repo/branch string              ; REQUIRED: Target branch
  :repo/type keyword}              ; REQUIRED: :terraform, :kubernetes, :application

 :workflow/context {...}           ; OPTIONAL: Domain-specific context (AWS region, etc.)

 :workflow/definition
 {:workflow/family keyword         ; REQUIRED: :software-factory | :etl | future families
  :workflow/profile keyword        ; REQUIRED: :canonical-sdlc | :quick-fix | :review-first | :financial-etl | ...
  :workflow/graph {...}            ; OPTIONAL: inline graph or reference-resolved graph
  :workflow/policies
  {:skip-phases #{keyword}         ; OPTIONAL: phases or states that may be skipped
   :retry-budgets map              ; OPTIONAL: per-state or per-transition budgets
   :auto-merge? boolean}}          ; OPTIONAL: release policy for eligible workflows

 :workflow/validation
 {:policy-packs [string ...]       ; REQUIRED: Policy packs to enforce
  :require-evidence? boolean       ; REQUIRED: Generate evidence bundle?
  :semantic-intent-check? boolean  ; REQUIRED: Validate semantic intent?
  :slo-overrides map}              ; OPTIONAL: Per-workflow SLO target overrides (see N1 §5.5.3)
 
 :workflow/runtime
 {:max-inner-loop-iterations long  ; OPTIONAL: Override default retry budget
  :checkpoint-policy map}}         ; OPTIONAL: Snapshot/checkpoint configuration
```

#### 9.1.1 ETL Workflow Type

The `:etl` workflow type is used to normalize existing repositories into safe, schema-valid packs.

ETL workflows SHOULD emit:

- `:feature-pack` units (normalized intent + acceptance criteria)
- `:policy-pack` units (deterministic rules extracted or generated)
- `:agent-profile-pack` units (optional)
- `:pack-index` manifest (hashes, trust labels, provenance)

ETL workflows MUST default generated packs to `:untrusted` unless explicitly promoted under policy.

**Custom Phase Sequence:** ETL workflows use a fundamentally different process than
standard software-factory workflows. While the canonical SDLC sequence is
(Plan → Design → Implement → Verify → Review → Release → Observe), ETL workflows
MAY define custom phases appropriate to the ingestion and normalization process,
provided those phases are still compiled into a single authoritative execution machine.
(e.g., Inventory → Classify → Scan → Extract → Validate → Index). The workflow
extensibility model treats ETL as just another workflow type with its own phase graph.

Recommended `:workflow/context` for ETL:

```clojure
{:etl/source {:repo/path string}
 :etl/output {:pack-root string
              :report-root string}
 :etl/options {:strict? boolean
               :max-files long}}
```

### 9.2 Workflow Spec Validation

Implementations MUST validate:

1. All required fields present
2. Intent type is valid
3. Target repository is accessible
4. Policy packs exist and are valid
5. Constraints are well-formed

---

## 10. Conformance & Testing

### 10.1 Phase Execution Conformance

Implementations MUST pass these tests:

1. **Sequential execution** - Phases execute in correct order
2. **Context handoff** - Context correctly passed between phases
3. **Gate enforcement** - Gates block phase completion on failure
4. **Inner loop validation** - Validation failures trigger repair
5. **Event emission** - All phase transitions emit events

### 10.2 Inner Loop Conformance

Implementations MUST demonstrate:

1. **Validation before completion** - Phase never completes without validation
2. **Repair attempts** - At least one repair strategy attempted
3. **Retry budget** - Loop terminates after max iterations
4. **Escalation** - Human prompted when budget exhausted

### 10.3 Semantic Intent Conformance

Implementations MUST correctly detect:

1. **Intent violations** - `:import` with creates flagged
2. **No false positives** - Valid workflows pass validation
3. **Accurate categorization** - Terraform/K8s changes correctly parsed

### 10.4 Conformance Requirements

Requirement IDs are stable identifiers for the normative statements of this
spec, so a conformance suite can cite what it tests. IDs are never reused; a
withdrawn requirement is marked withdrawn, not deleted.

#### Lifecycle and machine authority

| ID | Level | Requirement |
|----|-------|-------------|
| N2.LC.1 | MUST | Execute each run against exactly one compiled execution machine (§2.1). |
| N2.LC.2 | MUST NOT | Split authority between a lifecycle FSM and ad hoc phase-index or redirect logic (§2.1). |
| N2.LC.3 | MUST | Derive `:workflow/status` and `:workflow/current-phase` as projections, never as independent state (§2.1). |
| N2.LC.4 | MUST | Project exactly the status vocabulary of §2.2, introducing no synonym. |
| N2.LC.5 | MUST | Map every internal machine state deterministically onto that projection (§2.2). |
| N2.LC.6 | MUST NOT | Transition a terminal run back to an active state (§2.2, §8.1). |
| N2.LC.7 | MUST | Emit the lifecycle events of §2.4 and the checkpoint/resume family of N3 §3.21. |

#### Phase execution and inner loop

| ID | Level | Requirement |
|----|-------|-------------|
| N2.PH.1 | MUST | Execute phases in the order the compiled graph defines (§3, §4). |
| N2.PH.2 | MUST | Pass context between phases per the handoff contract (§4). |
| N2.PH.3 | MUST NOT | Complete a phase without validation (§5). |
| N2.PH.4 | MUST | Terminate the inner loop at the retry budget and escalate rather than loop (§5). |
| N2.PH.5 | MUST | Record the typed phase outcome on the event stream per N3 §3.1. |

#### Gates

| ID | Level | Requirement |
|----|-------|-------------|
| N2.GT.1 | MUST | Block phase completion on gate failure (§6). |
| N2.GT.2 | MUST | Emit `gate/started` and exactly one of `gate/passed` / `gate/failed` per execution (N3 §3.9, N4 §5.5). |
| N2.GT.3 | MUST | Record gate execution evidence sufficient to reproduce the result (N6 §2.13, N4 §5.5). |

#### Resumption

| ID | Level | Requirement |
|----|-------|-------------|
| N2.RS.1 | MUST | Resume a non-terminal interrupted run from its authoritative snapshot (§8.1). |
| N2.RS.2 | MUST NOT | Resume a terminal run; re-running produces a new `:workflow/id` (§8.1, §8.3). |
| N2.RS.3 | MUST | Compare the spec hash on resume and emit `workflow/spec-hash-mismatch` on divergence (§8.2). |
| N2.RS.4 | MUST | Preserve run identity on resume — same `:workflow/id`, no sequence reset, no re-emitted `workflow/started` (§8.2). |
| N2.RS.5 | MUST | Emit `workflow/resumed` recording resumed-from state, phase, and skipped phases (§8.2). |
| N2.RS.6 | MUST | Refuse a stale snapshot per §8.4, reporting which condition applied and leaving the run's state unchanged. |

### 10.5 Test Obligations

A conformance suite MUST cover, at minimum:

1. **Status vocabulary** — every status a run can reach is a member of §2.2's
   set; no surface emits `:pending` or `:executing` (N2.LC.4).
2. **Terminality** — resume is refused for each of `:completed`, `:failed`, and
   `:cancelled`, and re-running produces a distinct `:workflow/id`
   (N2.LC.6, N2.RS.2).
3. **Pause round-trip** — a paused run reports `:paused`, the transition is
   observable on the event stream, and resuming returns it to `:running`
   (N2.LC.4, N2.LC.7).
4. **Resume identity** — a crashed-and-resumed run has one `workflow/started`,
   a monotonic unbroken sequence, and a `workflow/resumed` naming the skipped
   phases (N2.RS.4, N2.RS.5).
5. **Spec drift** — resuming against a changed spec emits
   `workflow/spec-hash-mismatch` and applies the delta's disposition
   (N2.RS.3).
6. **Staleness refusal** — each of §8.4's three conditions produces a refusal
   that names the condition and leaves the run non-terminal (N2.RS.6).
7. **Projection consistency** — `:workflow/status` derived from the machine
   matches the supervisory projection for the same run at every transition
   (N2.LC.3, N2.LC.5).

---

## 11. Example Workflow Execution

### 11.1 RDS Import Workflow (Complete Trace)

```clojure
;; 1. Workflow spec
{:workflow/type :infrastructure-change
 :workflow/intent
 {:intent/type :import
  :intent/description "Import existing RDS instance to Terraform state"
  :intent/business-reason "Enable infrastructure-as-code management"
  :intent/constraints [{:constraint/type :no-resource-creation}
                       {:constraint/type :no-resource-destruction}]}
 :workflow/target
 {:repo/url "https://github.com/acme/terraform"
  :repo/branch "main"
  :repo/type :terraform}
 :workflow/context
 {:aws/region "us-east-1"
  :aws/rds-instance-id "acme-prod-postgres"}
 :workflow/validation
 {:policy-packs ["terraform-aws" "foundations"]
  :require-evidence? true
  :semantic-intent-check? true}}

;; 2. Plan phase executes
;;    - Planner analyzes intent
;;    - Creates plan: "Use Terraform import blocks"
;;    - Identifies risks: "State drift if import fails"
;;    - Plan artifact created

;; 3. Design phase SKIPPED (low complexity)

;; 4. Implement phase executes
;;    - Implementer generates Terraform import blocks
;;    - Inner loop iteration 1:
;;      - Validation: FAIL - accidentally included resource block
;;      - Repair: Remove resource block, keep only import block
;;    - Inner loop iteration 2:
;;      - Validation: PASS
;;    - Policy gate: PASS (no violations)
;;    - Semantic intent gate: PASS (0 creates, 0 destroys)
;;    - Code changes artifact created

;; 5. Verify phase executes
;;    - Tester runs terraform plan
;;    - Validates no infrastructure changes (state-only)
;;    - Test results: PASS
;;    - Test results artifact created

;; 6. Review phase executes
;;    - Reviewer checks semantic intent
;;    - Semantic validation: PASS (IMPORT intent matches IMPORT behavior)
;;    - Policy checks: PASS (all rules satisfied)
;;    - Review approved

;; 7. Release phase executes
;;    - Releaser creates PR
;;    - PR description includes evidence bundle link
;;    - PR created: #234

;; 8. Observe phase executes
;;    - Observer extracts signals
;;    - Pattern: "RDS imports are low-risk, 1 inner loop iteration avg"
;;    - Heuristic proposal: "Improve Implementer prompt for imports"
;;    - Knowledge base updated

;; 9. Workflow completes
;;    - Evidence bundle generated
;;    - Status: :completed
```

---

## 12. Rationale & Design Notes

### 12.1 Why Phase Graph?

The phase graph provides:

- **Clear structure** - Well-defined SDLC stages
- **Traceability** - Can see what happened in each phase
- **Modularity** - Can test/improve individual phases
- **Observability** - Phase transitions are natural event boundaries

### 12.2 Why Inner Loop?

The inner loop enables:

- **Autonomous repair** - Fix issues without human intervention
- **Quality assurance** - Don't proceed with invalid output
- **Learning** - Repair attempts generate signals for meta loop
- **Resilience** - Handles transient failures (e.g., LLM hallucinations)

### 12.3 Why Semantic Intent Validation?

Semantic intent validation catches:

- **Accidental drift** - Meant to import, accidentally created
- **Scope creep** - Started as update, evolved into create
- **Malicious changes** - Declared refactor, hiding a backdoor
- **Implementation errors** - Agent misunderstood intent

This is **unique to miniforge** - no other system validates intent vs. behavior.

---

## 13. DAG-Based Multi-Task Execution

### 13.1 Task Completion Definition

For DAG-based multi-task execution, **task completion is defined by integration terminal state**, not code generation.

The default terminal state is `:merged` — a task is not complete until its PR has been merged into the target branch.

```clojure
;; Task workflow terminal states
:merged    ; TERMINAL SUCCESS - PR merged into target branch
:failed    ; TERMINAL FAILURE - unrecoverable error or max retries exceeded
:skipped   ; TERMINAL SKIP - dependency failed or policy skip
```

Implementations MUST:

1. **Track task status through PR lifecycle** — not just code generation
2. **Consider dependencies satisfied** only when dependent tasks reach `:merged`
3. **Support partial completion** — some tasks merged, others failed/skipped

### 13.2 Task Workflow State Machine

Tasks progress through an extended state machine that encompasses the full PR lifecycle:

```text
:pending → :ready → :implementing → :pr-opening → :ci-running
                                                      ↓
                    :responding ← :review-pending ← ─┴─ (CI passed)
                         ↓                ↓
                    (fix pushed)    :ready-to-merge
                         ↓                ↓
                    :ci-running      :merging
                                          ↓
                                      :merged
```

Implementations MUST support these transitions:

| From State | To State | Trigger |
|------------|----------|---------|
| `:pending` | `:ready` | All dependencies merged |
| `:ready` | `:implementing` | Task dispatched |
| `:implementing` | `:pr-opening` | Inner loop complete |
| `:pr-opening` | `:ci-running` | PR created |
| `:ci-running` | `:review-pending` | CI passed |
| `:ci-running` | `:responding` | CI failed |
| `:review-pending` | `:ready-to-merge` | Approvals received |
| `:review-pending` | `:responding` | Changes requested |
| `:responding` | `:ci-running` | Fix pushed |
| `:ready-to-merge` | `:merging` | Merge initiated |
| `:merging` | `:merged` | Merge successful |
| Any non-terminal | `:failed` | Max retries exceeded, unrecoverable error |

This task lifecycle MUST itself be implemented as an explicit machine or transition table
with legal-state validation. Implementations MUST NOT permit unconstrained direct writes
to task status fields.

### 13.3 Automated CI/Review Fix Iteration

Implementations MUST support automated fix loops for CI failures and review feedback:

1. **On CI failure:**
   - Parse CI logs to identify failing tests/lint errors
   - Build fix context with failure details
   - Invoke inner loop with "fix" intent
   - Push fix commit and re-run CI

2. **On "changes requested" review:**
   - Triage comments as actionable vs non-actionable
   - Build fix context with actionable comments
   - Invoke inner loop with "fix" intent
   - Push fix commit and request re-review

Implementations MUST:

- Limit fix iterations (RECOMMENDED: max 5 per task)
- Limit CI retries (RECOMMENDED: max 3 per task)
- Escalate to human when limits exceeded
- Record all fix attempts in task evidence

### 13.4 Concurrency and Resource Constraints

For safe parallel execution of multiple tasks:

1. **Dependency constraints:** Task is ready only when all `:task/deps` are `:merged`

2. **Resource locks:**
   - `:repo-write` — Exclusive lock for repository writes (default: serialize)
   - `:exclusive-files` — Lock specific file paths to prevent conflicts

3. **Worktree isolation:** Each task SHOULD run in an isolated git worktree

Implementations MUST:

- Prevent concurrent modification of same files by different tasks
- Support configurable parallelism limits
- Release all locks on task completion or failure

#### 13.4.1 Frontier Computation

The **frontier** is the set of tasks eligible for dispatch at any point in time.

Implementations MUST compute the frontier as:

```text
frontier(run) = { t ∈ tasks | status(t) = :pending ∧ ∀d ∈ deps(t): status(d) = :merged }
```

Implementations MUST:

1. **Recompute frontier** after every task terminal transition (:merged, :failed, :skipped)
2. **Skip-propagate** — when a task reaches :failed, all transitive dependents MUST transition to :skipped
3. **Emit frontier events** — see N3 §3.12 for required event schemas

### 13.5 Rebase and Conflict Handling

When the base branch moves during task execution:

1. **Auto-rebase:** Attempt automatic rebase onto new base
2. **Conflict detection:** If rebase fails, detect conflicting files
3. **Conflict repair:** Invoke fix loop to resolve conflicts (per policy)
4. **Restart:** If API changes affect task, may restart from `:implementing`

### 13.6 Node Capability Contracts

Each task node in a DAG MAY declare a **capability contract** specifying required tools,
allowed file paths, and knowledge scope. When present, the contract constrains the agent
instance dispatched to execute the task.

#### 13.6.1 Capability Contract Schema

```clojure
{:task/capabilities
 {:cap/tools [keyword ...]          ; Tools agent may invoke (e.g., :write-file, :run-cmd, :gh-pr-create)
  :cap/paths [string ...]           ; Glob patterns for allowed file paths (e.g., "src/auth/**")
  :cap/knowledge [string ...]       ; Knowledge pack IDs available to agent
  :cap/archetype keyword            ; Agent archetype (e.g., :implementer, :tester, :reviewer)
  :cap/timeout-ms long              ; Max wall-clock time for task execution
  :cap/resource-locks [keyword ...] ; Resource locks required (from §13.4)
  :cap/idempotency-key string       ; OPTIONAL: stable key for safe retry/deduplication
  :cap/max-retries long             ; OPTIONAL: override default retry budget for this task
  :cap/success-predicate map        ; OPTIONAL: declarative predicate on task output (§13.6.5)
  :cap/compensation-fn keyword      ; OPTIONAL: compensation action if downstream fails (§13.6.4)
  }}
```

Fields are OPTIONAL. When omitted, the agent receives default capabilities for its archetype.

#### 13.6.2 Agent Scoping Rules

When a task declares capabilities, implementations MUST:

1. **Select archetype** — dispatch agent matching `:cap/archetype` (default: `:implementer`)
2. **Restrict tool set** — agent MUST only have access to tools listed in `:cap/tools`
3. **Restrict file scope** — file operations MUST be validated against `:cap/paths` globs
4. **Restrict knowledge** — agent context MUST only include packs listed in `:cap/knowledge`
5. **Enforce timeout** — kill agent if `:cap/timeout-ms` exceeded

When a task does NOT declare capabilities, the agent receives the full default tool set
for its archetype as defined in agent profile packs.

#### 13.6.3 Capability Inheritance

For tasks that share common requirements, implementations MAY support:

- **DAG-level defaults** — capabilities declared at DAG level apply to all tasks unless overridden
- **Task override** — task-level capabilities merge with (and override) DAG-level defaults
- **Archetype defaults** — agent profile packs define baseline capabilities per archetype

Merge order: archetype defaults → DAG defaults → task overrides.

#### 13.6.4 Compensation Protocol

When a task declares `:cap/compensation-fn`, the DAG executor MUST support compensating
actions for coordinated rollback across dependent tasks.

**Compensation requirements:**

1. The compensation action reference MUST be recorded at task completion (in evidence).
2. If a downstream dependent task fails AND the failure propagation policy requires
   rollback, the executor MUST invoke the compensation function for all upstream
   completed tasks in reverse dependency order.
3. Compensation invocations MUST be recorded in evidence bundles (see N6).
4. Compensation functions MUST be idempotent — invoking compensation twice MUST produce
   the same result as invoking it once.
5. Compensation failure MUST NOT mask the original failure — both MUST be recorded.

```clojure
;; Compensation invocation record (in evidence)
{:compensation/task-id uuid          ; Task being compensated
 :compensation/fn keyword            ; Compensation function invoked
 :compensation/trigger-task-id uuid  ; Downstream task whose failure triggered compensation
 :compensation/status keyword        ; :succeeded | :failed
 :compensation/timestamp inst}
```

Implementations MAY support configurable compensation policies at DAG level:

- `:compensate-all` — compensate all completed upstream tasks on any failure (default)
- `:compensate-none` — no automatic compensation; manual intervention required
- `:compensate-direct` — compensate only direct dependencies of the failed task

#### 13.6.5 Success Predicates

When a task declares `:cap/success-predicate`, the executor MUST evaluate the predicate
against the task output before transitioning to `:completed`. If the predicate fails, the
task transitions to `:failed` even if the tool execution itself returned success.

```clojure
;; Success Predicate Schema
{:predicate/type keyword          ; REQUIRED: :output-contains | :exit-code | :artifact-exists
                                  ;           :file-changed | :test-passed | :custom-fn
 :predicate/args map              ; REQUIRED: type-specific arguments
 :predicate/description string}   ; REQUIRED: human-readable explanation
```

**Predicate types:**

| Type | Args | Passes When |
|------|------|-------------|
| `:output-contains` | `{:key path :value any}` | Task output contains expected value at path |
| `:exit-code` | `{:expected int}` | Tool exited with expected code |
| `:artifact-exists` | `{:artifact-type keyword}` | Task produced an artifact of the specified type |
| `:file-changed` | `{:paths [string]}` | At least one of the specified files was modified |
| `:test-passed` | `{:suite keyword}` | Specified test suite passed |
| `:custom-fn` | `{:fn-ref keyword}` | Custom predicate function returns truthy |

Success predicate evaluation MUST be recorded in task evidence, including the predicate
definition, the actual values evaluated, and the pass/fail result.

---

## 14. Workflow Chaining

Workflow chaining enables the output of one workflow to serve as input to another,
creating composable pipelines. This is the mechanism by which Workflow Packs (N1 §2.24)
compose multi-step domain workflows.

### 14.1 Typed Outputs

Workflows MUST be able to emit typed outputs conforming to a declared output schema:

```clojure
{:workflow/outputs
 {:output/schema {...}                 ; EDN schema defining output shape
  :output/values {...}                 ; Actual output values
  :output/artifact-refs [uuid ...]}}   ; References to output artifacts
```

When a workflow is an entrypoint of a Workflow Pack, the output MUST conform to the
entrypoint's `:entrypoint/output-schema` (N1 §2.24).

### 14.2 Input Binding

Downstream workflows MUST be able to bind inputs to upstream workflow outputs:

```clojure
{:chain/bindings
 [{:binding/target-input string        ; Input parameter name in downstream workflow
   :binding/source-workflow-id uuid    ; Upstream workflow that produced the value
   :binding/source-output-path string  ; Path within upstream output values
   :binding/required? boolean}]}       ; FAIL chain if binding unresolvable
```

Implementations MUST:

1. Resolve bindings before starting the downstream workflow
2. Fail the chain edge if a required binding cannot be resolved
3. Record all resolved bindings in the chain edge evidence

### 14.3 Cross-Boundary Provenance

Chained execution MUST preserve provenance across workflow boundaries:

1. Each workflow in a chain MUST link to its upstream workflow(s) via chain edge references
2. Evidence bundles for downstream workflows MUST reference upstream evidence bundles
3. Artifact provenance chains MUST be traceable across workflow boundaries
4. The complete chain MUST be reconstructable from evidence alone

### 14.4 Chain Execution

```clojure
{:chain/id uuid                        ; REQUIRED: unique chain identifier
 :chain/edges
 [{:edge/id uuid                       ; REQUIRED: unique edge identifier
   :edge/from-workflow-id uuid         ; Upstream workflow
   :edge/to-workflow-id uuid           ; Downstream workflow
   :edge/bindings [...]                ; Input bindings (§14.2)
   :edge/status keyword                ; :pending, :running, :completed, :failed
   :edge/started-at inst
   :edge/completed-at inst}]}
```

Implementations MUST:

1. Emit chain edge events (see N3) for edge lifecycle transitions
2. Support pausing/retrying/rolling back at edge granularity via OCI (N8) when available
3. Record chain structure and edge results in evidence bundles (N6)

---

## 15. Future Extensions

### 15.1 Parallel Phase Execution (Post-OSS)

Future versions will support:

- Parallel execution of independent phases
- Conditional phase branching (if-then-else)
- Phase retry with backoff

### 15.2 Custom Phase Plugins (Enterprise)

Enterprise features will add:

- User-defined custom phases
- Phase plugin marketplace
- Phase composition (nested phases)

### 15.3 Cross-Workflow Dependencies (Enterprise)

PR train orchestration will enable:

- Multi-workflow phase synchronization
- DAG-based workflow execution
- Cross-workflow context sharing

---

## 16. References

- RFC 2119: Key words for use in RFCs to Indicate Requirement Levels
- N1 (Architecture): Defines core concepts (workflow, phase, agent, gate)
- N3 (Event Stream): Defines phase lifecycle events
- N4 (Policy Packs): Defines policy validation gates; capability enforcement via
  policy rules; §5.5 gate evidence obligations
- N6 (Evidence & Provenance): Defines evidence bundle generation; §2.13 records
  gate executions
- N2-delta-phase-checkpoint-and-resume: checkpoint and resume protocol (§8)
- N3 §3.21: checkpoint and resume event family emitted by §8
- N5-delta-supervisory-control-plane §3.2: supervisory projection of §2.2's
  status vocabulary
- N8 (Observability Control Interface): pause, resume, and cancel control actions

---

## Annex A — Implementation Conformance Status (informative)

This annex is **informative**. It records where the miniforge implementation
diverges from the contract above, as of 2026-08-06. It is not a relaxation of
any requirement in §1–§16.

### A.1 Status Vocabulary Divergence

The implementation emits `:workflow/status :executing`, which is not a member
of §2.2's set and never was a member of N5-delta-supervisory §3.2's either. It
also uses `:pending`, now a withdrawn synonym for `:queued`. Both need renaming
to the canonical vocabulary (N2.LC.4), and N5 §2.3.2's documented CLI filter
values need the same correction.

`:paused` and `:blocked` do appear in the tree, so the richer vocabulary is
partly implemented already — the gap is naming, not capability.

### A.2 Specified, Not Implemented

- **Checkpoint and resume events (§2.4, §8.2).** None of
  `workflow/checkpoint-written`, `workflow/checkpoint-write-failed`,
  `workflow/machine-snapshot-written`,
  `workflow/machine-snapshot-write-failed`, `workflow/resumed`, or
  `workflow/spec-hash-mismatch` is emitted anywhere. §8's resume protocol is
  therefore unobservable, and a failed resume cannot be audited
  (N2.LC.7, N2.RS.3, N2.RS.5).
- **Spec-hash comparison on resume (§8.2 step 2).** No implementation.
- **Staleness refusal (§8.4).** No resume window, no staleness check, no
  refusal reporting (N2.RS.6).

### A.3 Structural

- **Terminality is unenforced.** Nothing prevents a resume attempt against a
  terminal run (N2.LC.6, N2.RS.2).

**Version History:**

- 0.6.0-draft (2026-08-06): Spec-completion pass.
  **Lifecycle vocabulary unified (§2.2).** Three spellings were in use: N2 said
  `:pending`, N5-delta-supervisory §3.2 said `:queued`, and N5 §2.3.2's CLI
  filter plus the implementation said `:executing`. §2.2 is now the canonical
  set — `:queued :running :paused :blocked :completed :failed :cancelled` — and
  names `:pending` and `:executing` as withdrawn synonyms. `:paused` and
  `:blocked` were absent from the authority while N8 defined a pause action and
  the supervisory projection reported both.
  **Terminality made explicit (§2.2, §8).** Terminal states never reactivate;
  re-running produces a new `:workflow/id`. §8.1's "user cancelled and wants to
  restart" resume case is withdrawn — it contradicted terminality here and in
  N5-delta-supervisory §3.2, and would have left a cancelled run's evidence
  bundle describing a run that later continued.
  **Resume protocol completed (§8.2–§8.4).** Spec-hash comparison and the
  `workflow/spec-hash-mismatch`, `workflow/resumed` emissions wired to N3 §3.21
  and N2-delta §9; run identity preserved across resume; §8.3's unenforceable
  "too much time has passed" replaced by §8.4's three staleness conditions,
  with refusal required to name the condition and leave the run's state
  unchanged.
  **§2.4** extended with the checkpoint/resume event family and the requirement
  that pause and block transitions be observable.
  **§10.4–§10.5** conformance requirement IDs and test obligations.
  Annex A records implementation divergence.
- 0.5.0-draft (2026-03-08): Reliability Nines amendments — Workflow tier field in spec
  schema (§9.1), Capability contract extensions: idempotency key, success predicates,
  compensation protocol, max-retries (§13.6.1, §13.6.4, §13.6.5)
- 0.4.0-draft (2026-02-16): Added workflow chaining (§14) — typed outputs, input binding,
  cross-boundary provenance, chain execution
- 0.3.0-draft (2026-02-04): Added node capability contracts (§13.6) and frontier semantics (§13.4.1)
- 0.2.0-draft (2026-02-03): Added DAG-based multi-task execution model (Section 13)
- 0.1.0-draft (2026-01-23): Initial workflow execution model specification
