<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Miniforge Spec: Quality Readiness Assessment Workflow

## Status

Draft

## Classification

Normative workflow specification

## Purpose

Define a Miniforge workflow that evaluates a repository, application, service, or workflow implementation against a
structured quality-readiness rubric and produces:

- evidence-backed domain scores,
- red/orange/yellow/green status,
- blocker detection,
- confidence ratings,
- remediation recommendations,
- handoff artifacts for human review or downstream planning workflows.

This turns a human readiness checklist into a machine-runnable governance capability.

---

## 1. Goals

The workflow SHALL:

1. inspect a target codebase and related artifacts,
2. infer the target profile and criticality,
3. collect evidence relevant to readiness dimensions,
4. score the target against a weighted rubric,
5. identify blockers and missing evidence,
6. produce a normalized scorecard artifact,
7. optionally route output to human vetting or remediation-planning workflows.

The workflow SHOULD:

1. support multiple target profiles,
2. distinguish missing evidence from negative evidence,
3. emit confidence separately from score,
4. support organization-specific overrides,
5. support iterative refinement as more artifacts are provided.

---

## 2. Non-goals

This workflow is not intended to:

- replace all human architecture or release review,
- prove correctness formally,
- guarantee production safety,
- or act as a universal compliance framework without profile customization.

---

## 3. Primary use cases

### 3.1 Vibe-coded application assessment

Assess a newly created app with shallow documentation and uncertain test quality, then route gaps into planning or
test-generation workflows.

### 3.2 Repository release-readiness evaluation

Assess a service or repo before broader release or production promotion.

### 3.3 Miniforge workflow evaluation

Score a workflow itself for observability, determinism, policy posture, testing, error handling, and operational
readiness.

### 3.4 Portfolio triage

Evaluate many repos or work items to determine where human review attention should be concentrated.

---

## 4. Architectural model

The workflow SHALL use **rubric-driven evaluation with decision routing**.

It SHALL NOT use a pure binary decision tree as the core evaluation mechanism.

### 4.1 Stages

1. Inventory
2. Classification
3. Evidence extraction
4. Scoring
5. Synthesis
6. Routing
7. Artifact emission

---

## 5. Workflow contract

### 5.1 Inputs

```edn
{:target/id string
 :target/name string
 :target/path string
 :target/ref (or string nil)
 :target/type (or keyword nil)
 :target/context {:repo-url (or string nil)
                  :default-branch (or string nil)
                  :language-hints [keyword]
                  :artifact-paths [string]}
 :evaluation/profile (or keyword nil)
 :evaluation/criticality (or keyword nil)
 :evaluation/mode keyword
 :artifacts {:specs [string]
             :docs [string]
             :plans [string]
             :ci-configs [string]
             :runtime-configs [string]
             :test-results [string]
             :coverage-reports [string]
             :perf-reports [string]
             :security-reports [string]}
 :options {:allow-inference? boolean
           :require-citations? boolean
           :emit-remediation-plan? boolean
           :route-after-score? boolean
           :strict-blockers? boolean}}
```

### 5.2 Input behavior

The workflow SHALL operate with partial inputs.

If evidence is sparse, it SHALL:

- reduce confidence,
- distinguish absence of evidence from explicit failure,
- avoid over-claiming readiness.

### 5.3 Outputs

```edn
{:evaluation/id string
 :target {:name string
          :type keyword
          :criticality keyword}
 :summary {:overall-score number
           :overall-status keyword
           :confidence keyword
           :release-recommendation keyword}
 :profiles {:applied-profile keyword
            :weight-set keyword}
 :blockers [blocker]
 :domains [domain-score]
 :missing-evidence [missing-evidence]
 :negative-evidence [negative-evidence]
 :contradictions [contradiction]
 :recommended-actions [action]
 :routing {:next-step keyword
           :candidate-workflows [keyword]}
 :provenance {:evaluated-at inst
              :workflow-version string
              :inputs-digested [string]}}
```

---

## 6. Domain model

### 6.1 Default domains

1. `:product-clarity`
2. `:delivery-planning`
3. `:implementation-surface`
4. `:test-strategy`
5. `:operational-readiness`
6. `:security-and-privacy`
7. `:user-and-customer-readiness`
8. `:quality-evidence`
9. `:risk-and-governance`

### 6.2 Domain shape

```edn
{:id keyword
 :label string
 :weight number
 :status keyword
 :score number
 :confidence keyword
 :criteria [criterion-score]
 :strengths [string]
 :gaps [string]
 :blockers [blocker]
 :evidence [evidence-ref]
 :recommended-actions [action]}
```

### 6.3 Criterion shape

```edn
{:id keyword
 :label string
 :score integer
 :confidence keyword
 :status keyword
 :reason string
 :evidence [evidence-ref]
 :missing-evidence [string]
 :negative-evidence [string]}
```

---

## 7. Scoring semantics

### 7.1 Atomic criterion scale

- `0` = absent or directly contradicted
- `1` = mentioned, implied, or weakly addressed
- `2` = partially implemented with incomplete evidence
- `3` = credibly implemented with reasonable evidence
- `4` = strong implementation with explicit verification and/or automation

### 7.2 Confidence

Confidence SHALL be separate from score.

Allowed values:

- `:low`
- `:medium`
- `:high`

Interpretation:

- `:low` = mostly inferred or weak evidence
- `:medium` = mixed direct and indirect evidence
- `:high` = direct and corroborated evidence

### 7.3 Status mapping

- `:red` if domain score < 1.5
- `:orange` if 1.5 <= score < 2.5
- `:yellow` if 2.5 <= score < 3.25
- `:green` if score >= 3.25

Implementations MAY include `:gray` when evidence is insufficient.

### 7.4 Hard-blocker overrides

The workflow SHALL support blocker rules that cap or force status.

Examples:

- internet-exposed service with no rollback/disable strategy,
- auth/PII-sensitive app with no security/privacy evidence,
- production migration with no recovery story,
- critical workflow with no test or observability evidence,
- no clear owner or acceptance criteria for high-impact work.

---

## 8. Evidence taxonomy

The workflow SHALL distinguish among:

### 8.1 Positive evidence

Artifacts directly supporting readiness.
Examples:

- tests
- CI definitions
- dashboards
- runbooks
- threat models
- migration plans

### 8.2 Missing evidence

Expected evidence that is absent.
Examples:

- no rollback documentation
- no load-test result
- no feature-flag evidence

### 8.3 Negative evidence

Artifacts suggesting weakness.
Examples:

- hardcoded secrets
- no timeout handling
- flaky tests without mitigation
- broad permissions by default

### 8.4 Contradictory evidence

Claims undermined by artifacts.
Examples:

- docs claim retries but code shows none
- coverage goals exist but no coverage reports
- observability claimed but no metrics/logging hooks present

---

## 9. Profile system

The workflow SHALL support profile-based weighting and criterion activation.

### 9.1 Default profiles

- `:internal-vibe-app`
- `:customer-facing-saas`
- `:service-or-api`
- `:library-or-sdk`
- `:platform-or-infra`
- `:miniforge-workflow`

### 9.2 Profile behavior

Each profile SHALL define:

- active domains,
- domain weights,
- blocker rules,
- optional criteria,
- routing recommendations.

### 9.3 Example weighting

```edn
{:profile :miniforge-workflow
 :weights {:product-clarity 0.8
           :delivery-planning 0.6
           :implementation-surface 1.0
           :test-strategy 1.3
           :operational-readiness 1.3
           :security-and-privacy 1.2
           :user-and-customer-readiness 0.4
           :quality-evidence 1.2
           :risk-and-governance 1.2}}
```

---

## 10. Evidence extraction requirements

The workflow SHALL attempt to discover and normalize, where present:

### 10.1 Repo and implementation inventory

- languages/frameworks
- package/build files
- component structure
- API definitions
- UI surface presence
- jobs/workflows/pipelines

### 10.2 Delivery artifacts

- CI/CD configs
- test suites and reports
- coverage artifacts
- performance artifacts
- deployment manifests
- infra definitions
- migrations

### 10.3 Operational signals

- logging hooks
- metrics/traces
- health checks
- feature flags
- backup/recovery mechanisms
- runbooks

### 10.4 Security/trust signals

- secret handling
- dependency/security scanning config
- threat model docs
- authn/authz signals
- audit/event logging

### 10.5 Planning/intent artifacts

- specs
- ADRs
- PRDs
- acceptance criteria
- release plans
- work plans

---

## 11. Domain criteria (default)

### 11.1 `:product-clarity`

- purpose stated
- intended users identified
- acceptance criteria defined
- scope/non-goals articulated
- expected outcomes stated

### 11.2 `:delivery-planning`

- owner/team identified
- dependencies identified
- responsibilities clear
- status/reporting expectations defined where needed
- rollout or milestone sequence exists

### 11.3 `:implementation-surface`

- impacted services/components identified
- configs/manifests/runtime modes understood
- integrations identified
- migration/upgrade surface understood
- compatibility assumptions visible

### 11.4 `:test-strategy`

- relevant test layers exist
- major scenarios identified
- failure modes considered
- smoke/CIT/BVT equivalents exist where appropriate
- environment/setup requirements are explicit

### 11.5 `:operational-readiness`

- deployment path exists
- rollback/disable strategy exists
- observability is present
- recovery/operability considerations exist
- performance/scalability considered where required

### 11.6 `:security-and-privacy`

- sensitive boundaries identified
- security strategy or review exists
- privacy implications addressed
- secrets handled correctly
- abuse/error paths considered

### 11.7 `:user-and-customer-readiness`

- UI testing/manual review exists if UI present
- accessibility considered if relevant
- localization considered if relevant
- beta/feedback loop exists if relevant
- customer-facing behavior is documented

### 11.8 `:quality-evidence`

- coverage evidence exists where relevant
- performance/profiling evidence exists where relevant
- defect/robustness trends considered
- scenario/stress evidence exists where needed
- reliability evidence available

### 11.9 `:risk-and-governance`

- risks identified
- mitigations documented
- exceptions explicit
- release criteria stated
- blocker conditions understood

---

## 12. Routing logic

The workflow SHALL support decision routing after scoring.

### 12.1 Default routing states

- `:proceed`
- `:proceed-with-conditions`
- `:human-vet`
- `:plan-remediation`
- `:block`

### 12.2 Example routing rules

- If overall is `:red`, route to `:block` and recommend remediation.
- If overall is `:orange` with medium/high confidence, route to `:plan-remediation`.
- If overall is `:yellow` with no blockers, route to `:proceed-with-conditions`.
- If overall is `:green` with low confidence, route to `:human-vet`.
- If profile is `:miniforge-workflow` and `:test-strategy` or `:operational-readiness` are below Yellow, cap next-step
  at human review or remediation.

---

## 13. Handoff contracts

### 13.1 Remediation planning handoff

```edn
{:handoff/type :quality-remediation
 :priorities [{:priority :p0
               :domain :security-and-privacy
               :title string
               :why string
               :evidence [evidence-ref]
               :definition-of-done [string]}]}
```

### 13.2 Human review handoff

The workflow SHALL emit a compact review packet containing:

- overall status,
- blockers,
- high-risk domains,
- low-confidence domains,
- recommended reviewer questions.

### 13.3 Test-generation handoff

Where gaps are test-related, the workflow SHOULD emit candidate scenario and test targets.

---

## 14. Workflow decomposition

This capability SHOULD be implemented as cooperating workflows.

### 14.1 `quality-readiness-inventory`

Produces normalized evidence graph and target classification.

### 14.2 `quality-readiness-score`

Applies rubric, weights, blockers, and domain synthesis.

### 14.3 `quality-remediation-plan`

Converts deficits into backlog/spec/test/doc work.

### 14.4 `quality-vetting-gate`

Determines automated progression vs human review.

---

## 15. State model

```edn
{:states [:inventory
          :classify
          :extract-evidence
          :score-domains
          :synthesize
          :route
          :emit-artifacts
          :done
          :failed]}
```

Transition constraints:

- `:classify` requires inventory output
- `:extract-evidence` requires classification output
- `:score-domains` requires evidence map
- `:synthesize` requires domain scores
- `:route` requires synthesized result
- `:emit-artifacts` requires routing decision

---

## 16. Failure semantics

The workflow SHALL fail explicitly when:

- target path or artifact access is invalid,
- scoring configuration is malformed,
- required profile data is unavailable,
- evidence extraction cannot complete and strict mode is enabled.

The workflow SHOULD degrade gracefully when:

- some artifact classes are missing,
- source repos are incomplete,
- reports are stale or partial.

---

## 17. Observability requirements for the evaluator

Because this is a governance workflow, it SHALL emit its own execution telemetry:

- stage timing
- evidence counts
- missing-evidence counts
- blocker counts
- routing decision
- confidence distribution
- failure reasons

---

## 18. Safety requirements

The workflow SHALL:

- avoid unsupported certainty,
- cite evidence for consequential claims,
- label inferred findings as inferred,
- separate score from confidence,
- avoid Green in the presence of unresolved critical blockers.

The workflow SHOULD:

- preserve reviewer override hooks,
- allow org-specific blocker policy,
- support explanation-friendly outputs.

---

## 19. Example output

```edn
{:evaluation/id "qra-2026-04-04-001"
 :target {:name "lesson-service"
          :type :service-or-api
          :criticality :high}
 :summary {:overall-score 2.38
           :overall-status :orange
           :confidence :medium
           :release-recommendation :plan-remediation}
 :blockers [{:id :missing-rollback-strategy
             :severity :high
             :domain :operational-readiness
             :summary "Production deployment path found, but no rollback or disable strategy was evidenced."}]
 :domains [{:id :test-strategy
            :label "Test Strategy"
            :weight 1.3
            :score 2.1
            :status :orange
            :confidence :medium
            :strengths ["Unit and API tests present"]
            :gaps ["No end-to-end evidence for critical flows"
                   "No migration verification evidence"]
            :recommended-actions [{:priority :p1
                                   :title "Add integration and migration verification suite"}]}
           {:id :security-and-privacy
            :label "Security and Privacy"
            :weight 1.2
            :score 2.7
            :status :yellow
            :confidence :medium}]
 :missing-evidence [{:domain :operational-readiness
                     :item "rollback-plan"}
                    {:domain :quality-evidence
                     :item "performance-baseline"}]
 :routing {:next-step :plan-remediation
           :candidate-workflows [:quality-remediation-plan
                                 :generate-test-plan]}}
```

---

## 20. Why this is interesting

The interesting part is not the checklist. It is the conversion of institutional delivery judgment into:

- a machine-readable rubric,
- an evidence-backed evaluation pass,
- and a routing mechanism that can automatically drive work toward green.

That makes this a real governance primitive for an autonomous software factory rather than a static review document.
