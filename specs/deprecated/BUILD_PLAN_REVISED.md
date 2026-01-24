# miniforge.ai: Implementation Build Plan (REVISED)

**Date:** 2026-01-22
**Goal:** Ship OSS Beta - Autonomous Software Factory with Local Fleet Management
**Timeline:** 8 weeks to OSS Beta
**Status:** Ready to build

---

## Executive Summary

### What We're Building (OSS)

**An autonomous software factory that runs on your local machine. Full SDLC automation from spec to production.**

**NOT a PR management tool. The PR dashboard is the operations console for watching the factory work.**

### Core OSS Architecture

**OSS Components** (Complete, Local):

- ✅ **schema** - Domain types (EXISTS)
- ✅ **logging** - EDN structured logging (EXISTS)
- ✅ **tool** - Tool protocol (EXISTS)
- ✅ **agent** - Agent runtime + specialized agents (EXISTS - 4,764 LOC)
- ✅ **task** - Task management (EXISTS)
- ✅ **loop** - Inner/outer loops with validation (EXISTS - 3,060 LOC)
- ✅ **workflow** - SDLC workflow engine (EXISTS - 6,503 LOC)
- ✅ **knowledge** - Zettelkasten local learning (EXISTS - 1,965 LOC)
- ✅ **policy** - Policy engine (EXISTS - 3,401 LOC)
- ✅ **heuristic** - Basic registry (EXISTS)
- ✅ **artifact** - Local artifact store (EXISTS)
- 🔨 **cli** - Single-user CLI (EXISTS, enhance)
- 🔨 **local-fleet** - Local operations console TUI/Web (TO BUILD)

**Explicitly OUT of OSS** (Paid Features):

- ❌ **repo-dag** - Multi-repo dependency graph (PAID)
- ❌ **pr-train** - Linked PR choreography (PAID)
- ❌ **authz** - RBAC, approvals, audit trail (PAID)
- ❌ **policy-distribution** - Org-wide policy packs (PAID)
- ❌ **eval-harness** - Canary/shadow evaluation (PAID)
- ❌ **telemetry** - Org analytics, MTTR tracking (PAID)
- ❌ **operators-console** - Multi-user web UI (PAID)
- ❌ **fleet-daemon** - Remote runners (PAID)

### Strategic Differentiators (Build These FIRST)

1. **Autonomous SDLC Execution** - Spec → Agents → Code → Production
2. **Multi-Agent Cognition** - Agents as teammates, not tools
3. **Self-Improving Meta Loop** - Learns from execution
4. **Full Provenance & Semantic Intent** - Trace back to business requirement
5. **Compliance-Ready Architecture** - SOCII, FedRAMP foundations
6. **Evidence Bundles** - Queryable artifact history
7. **Policy-as-Code with Semantic Validation** - IMPORT intent ≠ CREATE

### Timeline

**8 weeks to OSS Beta** (late March 2026)

- **Week 1-2:** Autonomous Workflow Execution (Outer Loop Integration)
- **Week 3-4:** Multi-Agent Coordination & Context Handoff
- **Week 5-6:** Self-Improvement Loop & Evidence Bundles
- **Week 7:** Local Fleet Operations Console (TUI)
- **Week 8:** OSS Packaging, Documentation & Beta Launch

---

## Week 1-2: Autonomous Workflow Execution (Jan 22 - Feb 5)

### Goal

End-to-end autonomous workflow: Write spec file → Agents execute SDLC → PRs created → Evidence generated

**This is the CORE VALUE. Everything else is monitoring.**

### Current State Assessment

**What EXISTS (37K LOC across 23 components):**

- ✅ Workflow engine with outer loop phases
- ✅ All specialized agents (Planner, Implementer, Tester, Reviewer)
- ✅ Inner loop validation
- ✅ Policy engine with scanner protocol
- ✅ Task management
- ✅ Local knowledge base (Zettelkasten)
- ✅ Artifact store
- ✅ Tool protocol

**What NEEDS WORK:**

- 🔨 Integration tests proving end-to-end flow
- 🔨 Spec file format & validation
- 🔨 Agent context handoff between phases
- 🔨 Evidence bundle generation
- 🔨 Artifact provenance tracking

### Day 1-3: End-to-End Integration

**Objective:** Prove autonomous execution works from spec to PR

#### Spec File Format

```clojure
;; specs/rds-import.edn

{:workflow/type :infrastructure-change
 :workflow/intent {:intent/type :import
                   :intent/description "Import existing RDS instance to Terraform state"
                   :intent/business-reason "Enable infrastructure-as-code management"}

 :workflow/target {:repo/url "https://github.com/acme/terraform"
                   :repo/branch "main"
                   :repo/type :terraform}

 :workflow/constraints [{:constraint/type :no-resource-creation
                         :constraint/description "Must only import, not create new resources"}
                        {:constraint/type :no-resource-destruction
                         :constraint/description "Must not destroy any existing resources"}]

 :workflow/validation {:policy-packs ["terraform-aws" "foundations"]
                       :require-evidence true
                       :semantic-intent-check true}

 :workflow/context {:aws/region "us-east-1"
                    :aws/rds-instance-id "acme-prod-postgres"
                    :terraform/resource-address "aws_db_instance.main"}}
```

#### Integration Test: Full Autonomous Flow

```clojure
;; tests/integration/autonomous_workflow_test.clj

(ns ai.miniforge.tests.integration.autonomous-workflow
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.workflow.interface :as workflow]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.policy.interface :as policy]
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.knowledge.interface :as knowledge]))

(deftest autonomous-rds-import-workflow
  (testing "Full autonomous workflow execution: spec → agents → PRs → evidence"
    (let [;; Load spec
          spec (read-spec "specs/rds-import.edn")

          ;; Create workflow engine
          wf-engine (workflow/create-engine)

          ;; Initialize agents
          planner (agent/create-planner)
          implementer (agent/create-implementer)
          tester (agent/create-tester)
          reviewer (agent/create-reviewer)

          ;; Initialize supporting systems
          policy-reg (policy/create-registry)
          artifact-store (artifact/create-store)
          kb (knowledge/create-knowledge-base)

          ;; Load policy packs
          _ (policy/register-pack policy-reg (load-policy-pack "terraform-aws"))
          _ (policy/register-pack policy-reg (load-policy-pack "foundations"))

          ;; Execute workflow
          execution (workflow/execute wf-engine spec
                                      {:agents {:planner planner
                                               :implementer implementer
                                               :tester tester
                                               :reviewer reviewer}
                                       :policy-registry policy-reg
                                       :artifact-store artifact-store
                                       :knowledge-base kb})]

      ;; Assert: Workflow completed
      (is (= :completed (:workflow/status execution)))

      ;; Assert: All phases executed
      (is (= #{:plan :design :implement :verify :review}
             (set (keys (:workflow/phases execution)))))

      ;; Assert: Evidence bundle created
      (let [evidence (:workflow/evidence execution)]
        (is (:evidence/intent evidence))
        (is (:evidence/plan evidence))
        (is (:evidence/implementation evidence))
        (is (:evidence/validation evidence))
        (is (:evidence/outcome evidence)))

      ;; Assert: Semantic intent validation passed
      (let [intent-check (:workflow/intent-validation execution)]
        (is (:passed? intent-check))
        (is (= :import (get-in intent-check [:intent/declared])))
        (is (= :import (get-in intent-check [:intent/actual])))
        (is (zero? (:resource-creates intent-check)))
        (is (zero? (:resource-destroys intent-check))))

      ;; Assert: Policy checks passed
      (let [policy-results (:workflow/policy-results execution)]
        (is (every? :passed? policy-results))
        (is (empty? (mapcat :violations policy-results))))

      ;; Assert: PR created with evidence
      (let [pr (:workflow/pr execution)]
        (is (:pr/number pr))
        (is (:pr/url pr))
        (is (:pr/branch pr))
        (is (= (:workflow/id execution) (:pr/workflow-id pr))))

      ;; Assert: Artifacts stored with provenance
      (let [artifacts (artifact/list-artifacts artifact-store (:workflow/id execution))]
        (is (some #(= :terraform-plan (:artifact/type %)) artifacts))
        (is (every? :artifact/provenance artifacts))
        (is (every? #(= (:workflow/id execution)
                        (get-in % [:artifact/provenance :workflow/id]))
                    artifacts)))

      ;; Assert: Knowledge captured
      (let [learnings (knowledge/query kb {:workflow/id (:workflow/id execution)})]
        (is (seq learnings))
        (is (some #(= :learning (:knowledge/type %)) learnings))))))

(deftest autonomous-workflow-with-failure-recovery
  (testing "Workflow handles failures and repairs via inner loop"
    ;; Test that inner loop catches validation failures and repairs
    ))

(deftest semantic-intent-violation-detected
  (testing "Workflow catches intent mismatch (declares IMPORT, actually CREATEs)"
    ;; Test that semantic validation blocks mismatched intent
    ))
```

**Exit Criteria:**

- [ ] Spec file loads and validates
- [ ] Workflow executes all outer loop phases autonomously
- [ ] Agents hand off context correctly between phases
- [ ] Evidence bundle generated with full provenance
- [ ] Semantic intent validation works
- [ ] Policy checks execute at each gate
- [ ] PR created with evidence linked
- [ ] Artifacts stored with queryable provenance

**Deliverable:** `make integration-test` passes autonomous workflow test

### Day 4-7: Agent Context Handoff

**Problem:** Agents need to pass rich context between phases

#### Context Schema

```clojure
;; components/agent/src/ai/miniforge/agent/context.clj

{:agent-context/phase :implement
 :agent-context/prior-phases {:plan {...}
                               :design {...}}

 ;; From Planner
 :plan/approach "Use terraform import to add existing RDS to state"
 :plan/steps [{:step/id 1 :step/description "..."}]
 :plan/risks [{:risk/description "..." :risk/mitigation "..."}]

 ;; From Designer (if exists)
 :design/architecture {...}
 :design/files-to-change ["main.tf" "rds.tf"]

 ;; Semantic Intent (from spec)
 :intent/type :import
 :intent/constraints [{:constraint/type :no-resource-creation}]

 ;; Policy Context
 :policy/active-packs ["terraform-aws" "foundations"]
 :policy/gate-phase :implement

 ;; Workflow Context
 :workflow/id #uuid "..."
 :workflow/spec {...}

 ;; Knowledge Context (from prior workflows)
 :knowledge/relevant-patterns [{:pattern/type :rds-import
                                :pattern/success-rate 0.95}]

 ;; Artifact Context
 :artifacts/from-prior-phases [{:artifact/type :plan-document
                                :artifact/content "..."}]}
```

#### Implementer Agent Enhancement

```clojure
;; components/agent/src/ai/miniforge/agent/implementer.clj

(defn execute-with-context
  "Execute implementer with full context from prior phases"
  [agent context]

  ;; Build comprehensive prompt with:
  ;; 1. Original intent & constraints
  ;; 2. Plan from Planner
  ;; 3. Design from Designer (if exists)
  ;; 4. Relevant patterns from knowledge base
  ;; 5. Policy requirements for this phase

  (let [prompt (build-implementer-prompt context)

        ;; Inner loop: Generate → Validate → Repair
        implementation (inner-loop/execute
                        agent
                        {:generate-fn (fn [] (generate-code agent prompt))
                         :validate-fn (fn [code] (validate-implementation
                                                   code
                                                   (:intent/constraints context)
                                                   (:policy/active-packs context)))
                         :repair-fn (fn [code violations] (repair-code agent code violations))
                         :max-iterations 5})

        ;; Generate artifacts
        artifacts (generate-artifacts implementation context)

        ;; Evidence for this phase
        evidence {:evidence/phase :implement
                  :evidence/context context
                  :evidence/implementation implementation
                  :evidence/artifacts artifacts
                  :evidence/validation (:validation implementation)}]

    {:implementation implementation
     :artifacts artifacts
     :evidence evidence
     :next-context (assoc context :implement/completed implementation)}))

(defn build-implementer-prompt [context]
  (str "You are the Implementer agent in an autonomous software factory.\n\n"
       "## Original Intent\n"
       "Type: " (name (get-in context [:intent/type])) "\n"
       "Description: " (get-in context [:intent/description]) "\n\n"
       "## Constraints (MUST SATISFY)\n"
       (pr-str (get-in context [:intent/constraints])) "\n\n"
       "## Plan from Planner Agent\n"
       (get-in context [:plan/approach]) "\n"
       "Steps:\n" (pr-str (get-in context [:plan/steps])) "\n\n"
       "## Relevant Patterns from Knowledge Base\n"
       (pr-str (get-in context [:knowledge/relevant-patterns])) "\n\n"
       "## Policy Requirements\n"
       "Active packs: " (pr-str (get-in context [:policy/active-packs])) "\n"
       "Your implementation will be checked against these policies.\n\n"
       "## Task\n"
       "Implement the changes following the plan. Ensure:\n"
       "1. All constraints are satisfied\n"
       "2. Code matches declared intent\n"
       "3. Policies will pass\n"
       "4. Evidence is generated\n\n"
       "Output: Complete implementation with explanations."))
```

**Exit Criteria:**

- [ ] Planner creates plan with context
- [ ] Implementer receives plan context and implements
- [ ] Tester receives implementation context and tests
- [ ] Reviewer receives all prior context and reviews
- [ ] Context includes semantic intent at every phase
- [ ] Knowledge base patterns included in context

**Deliverable:** Agents successfully hand off context through full workflow

### Day 8-10: Evidence Bundles & Artifact Provenance

**Objective:** Full audit trail from intent → plan → implementation → outcome

#### Evidence Bundle Schema

```clojure
;; components/artifact/src/ai/miniforge/artifact/evidence.clj

{:evidence-bundle/id #uuid "..."
 :evidence-bundle/workflow-id #uuid "..."
 :evidence-bundle/created-at inst
 :evidence-bundle/version 1

 ;; Original Intent
 :evidence/intent {:intent/type :import
                   :intent/description "..."
                   :intent/business-reason "..."
                   :intent/constraints [...]}

 ;; Phase Evidence
 :evidence/plan {:phase/name :plan
                 :phase/agent :planner
                 :phase/output {...}
                 :phase/artifacts [{:artifact/id ... :artifact/type :plan-document}]
                 :phase/timestamp inst}

 :evidence/design {:phase/name :design
                   :phase/agent :designer
                   :phase/output {...}
                   :phase/artifacts [{:artifact/id ... :artifact/type :architecture-diagram}]
                   :phase/timestamp inst}

 :evidence/implement {:phase/name :implement
                      :phase/agent :implementer
                      :phase/output {...}
                      :phase/artifacts [{:artifact/id ... :artifact/type :code-changes}
                                       {:artifact/id ... :artifact/type :terraform-plan}]
                      :phase/inner-loop-iterations 2
                      :phase/timestamp inst}

 :evidence/verify {:phase/name :verify
                   :phase/agent :tester
                   :phase/output {...}
                   :phase/artifacts [{:artifact/id ... :artifact/type :test-results}]
                   :phase/timestamp inst}

 :evidence/review {:phase/name :review
                   :phase/agent :reviewer
                   :phase/output {...}
                   :phase/artifacts [{:artifact/id ... :artifact/type :review-report}]
                   :phase/timestamp inst}

 ;; Validation Evidence
 :evidence/semantic-validation {:declared-intent :import
                                :actual-behavior :import
                                :resource-creates 0
                                :resource-updates 0
                                :resource-destroys 0
                                :passed? true
                                :checked-at inst}

 :evidence/policy-checks [{:policy-pack "terraform-aws"
                           :policy-pack-version "1.0.0"
                           :phase :implement
                           :violations []
                           :passed? true
                           :checked-at inst}]

 ;; Outcome
 :evidence/outcome {:pr/number 234
                    :pr/url "https://..."
                    :pr/status :merged
                    :pr/merged-at inst
                    :outcome/success true}

 ;; Compliance Metadata (for SOCII, FedRAMP)
 :compliance/auditor-notes "Reviewed for SOCII compliance"
 :compliance/sensitive-data false
 :compliance/pii-handling :none}
```

#### Queryable Provenance

```clojure
;; components/artifact/src/ai/miniforge/artifact/provenance.clj

(ns ai.miniforge.artifact.provenance
  (:require
   [ai.miniforge.artifact.interface :as artifact]))

(defn query-provenance
  "Query artifact provenance - trace back to original intent"
  [store artifact-id]

  (let [artifact (artifact/get-artifact store artifact-id)
        workflow-id (get-in artifact [:artifact/provenance :workflow/id])
        evidence-bundle (artifact/get-evidence-bundle store workflow-id)]

    {:artifact artifact
     :workflow-id workflow-id
     :original-intent (:evidence/intent evidence-bundle)
     :created-by-phase (get-in artifact [:artifact/provenance :phase/name])
     :created-by-agent (get-in artifact [:artifact/provenance :agent/id])
     :created-at (get-in artifact [:artifact/provenance :timestamp])
     :prior-artifacts (get-prior-artifacts evidence-bundle
                                           (get-in artifact [:artifact/provenance :phase/name]))
     :subsequent-artifacts (get-subsequent-artifacts evidence-bundle
                                                     (get-in artifact [:artifact/provenance :phase/name]))
     :validation-results (filter #(= (:phase %) (get-in artifact [:artifact/provenance :phase/name]))
                                 (:evidence/policy-checks evidence-bundle))
     :full-evidence-bundle evidence-bundle}))

(defn trace-artifact-chain
  "Trace full artifact chain from spec to outcome"
  [store workflow-id]

  (let [evidence-bundle (artifact/get-evidence-bundle store workflow-id)]
    {:intent (:evidence/intent evidence-bundle)
     :chain (for [phase [:plan :design :implement :verify :review]]
              (when-let [phase-evidence (get evidence-bundle (keyword "evidence" (name phase)))]
                {:phase phase
                 :agent (:phase/agent phase-evidence)
                 :artifacts (:phase/artifacts phase-evidence)
                 :timestamp (:phase/timestamp phase-evidence)}))
     :outcome (:evidence/outcome evidence-bundle)}))

;; Query examples:

;; "Show me all artifacts created in the implement phase"
(query-artifacts-by-phase store workflow-id :implement)

;; "What was the original intent for this terraform plan?"
(query-provenance store terraform-plan-artifact-id)
;; => {:original-intent {:intent/type :import ...}}

;; "Show me the full chain from intent to PR merge"
(trace-artifact-chain store workflow-id)

;; "Find all workflows where declared intent doesn't match actual behavior"
(query-intent-mismatches store)
```

**Exit Criteria:**

- [ ] Evidence bundle generated for every workflow
- [ ] All artifacts have provenance (workflow, phase, agent, timestamp)
- [ ] Can query: "What was the original intent for this artifact?"
- [ ] Can query: "Show me all artifacts from implement phase"
- [ ] Can trace full chain: spec → plan → code → tests → PR → outcome
- [ ] Compliance metadata captured (for SOCII/FedRAMP foundations)

**Deliverable:** Queryable artifact provenance system

### Week 1-2 Deliverables

**Working Autonomous System:**

- ✅ Write spec file → Agents execute autonomously → PR created
- ✅ Agent context handoff through all phases
- ✅ Evidence bundles with full provenance
- ✅ Semantic intent validation
- ✅ Policy checks at each gate
- ✅ Queryable artifact history

**Demo:**

```bash
# Create autonomous workflow
cat > specs/rds-import.edn <<EOF
{:workflow/type :infrastructure-change
 :workflow/intent {:intent/type :import
                   :intent/description "Import existing RDS to Terraform state"}
 :workflow/target {:repo/url "https://github.com/acme/terraform"
                   :repo/branch "main"}
 :workflow/constraints [{:constraint/type :no-resource-creation}]}
EOF

# Execute (fully autonomous)
miniforge workflow execute specs/rds-import.edn

# Watch it work (agents executing phases)
miniforge workflow status <workflow-id>

# Query results
miniforge artifact provenance <artifact-id>
# Shows: intent → plan → implementation → validation → outcome

# View evidence bundle
miniforge evidence show <workflow-id>
# Full audit trail with semantic validation
```

---

## Week 3-4: Multi-Agent Coordination & Inner Loop (Feb 5 - Feb 19)

### Goal

Agents work as teammates, not tools. Inner loop validation with repair.

### Multi-Agent Coordination

**Current State:** Agents exist but may not coordinate optimally

**Enhancement:** Agent memory, inter-agent communication, shared context

#### Agent Memory System

```clojure
;; components/agent/src/ai/miniforge/agent/memory.clj

(ns ai.miniforge.agent.memory
  (:require
   [ai.miniforge.knowledge.interface :as kb]))

(defn create-agent-memory
  "Create memory system for an agent"
  [agent-id knowledge-base]
  {:agent-memory/id (random-uuid)
   :agent-memory/agent-id agent-id
   :agent-memory/knowledge-base knowledge-base
   :agent-memory/working-memory {}  ; Current context
   :agent-memory/episodic-memory [] ; Past interactions
   :agent-memory/learned-patterns []})

(defn remember-interaction
  "Store interaction in episodic memory"
  [memory interaction]
  (update memory :agent-memory/episodic-memory conj
          {:interaction/timestamp (java.time.Instant/now)
           :interaction/data interaction}))

(defn recall-similar
  "Recall similar past interactions"
  [memory current-context]
  (let [kb (:agent-memory/knowledge-base memory)
        similar (kb/query-similar kb current-context {:limit 5})]
    similar))

(defn learn-from-outcome
  "Update learned patterns based on workflow outcome"
  [memory workflow-id outcome]
  ;; Extract patterns from successful/failed workflows
  ;; Store in knowledge base
  )
```

#### Inter-Agent Communication

```clojure
;; components/agent/src/ai/miniforge/agent/communication.clj

(defn send-message
  "Send message from one agent to another"
  [from-agent to-agent message-type content]
  {:message/from from-agent
   :message/to to-agent
   :message/type message-type  ; :clarification-request, :concern, :suggestion
   :message/content content
   :message/timestamp (java.time.Instant/now)})

;; Example: Implementer asks Planner for clarification
(send-message :implementer :planner :clarification-request
              {:question "Should we create a new security group or reuse existing?"
               :context {:workflow-id ... :phase :implement}})

;; Planner responds with clarification
(send-message :planner :implementer :clarification-response
              {:answer "Reuse existing security group 'sg-prod-rds'"
               :reasoning "Spec specifies IMPORT intent - no new resources"})

;; Reviewer raises concern to Implementer
(send-message :reviewer :implementer :concern
              {:concern "Implementation creates new IAM role, violates IMPORT constraint"
               :severity :high
               :recommendation "Use existing role or escalate to human"})
```

**Exit Criteria:**

- [ ] Agents maintain episodic memory across workflow phases
- [ ] Agents can query knowledge base for similar past cases
- [ ] Agents can communicate (ask questions, raise concerns)
- [ ] Agent communication logged in evidence bundle

**Deliverable:** Coordinated multi-agent system with memory

### Inner Loop Enhancement

**Current State:** Inner loop exists in components/loop

**Enhancement:** Make it robust with multiple repair strategies

```clojure
;; components/loop/src/ai/miniforge/loop/repair.clj

(defn repair-with-strategies
  "Try multiple repair strategies in order"
  [agent artifact violations context]

  (let [strategies [{:strategy/name :direct-fix
                     :strategy/fn (fn [] (repair-direct agent artifact violations))}

                    {:strategy/name :ask-peer-agent
                     :strategy/fn (fn [] (ask-peer-for-help agent artifact violations context))}

                    {:strategy/name :consult-knowledge-base
                     :strategy/fn (fn [] (consult-kb-for-pattern agent artifact violations context))}

                    {:strategy/name :escalate-human
                     :strategy/fn (fn [] (escalate-to-human artifact violations))}]]

    (loop [remaining-strategies strategies
           iteration 1]
      (if (empty? remaining-strategies)
        {:repair/failed true
         :repair/reason "All strategies exhausted"
         :repair/escalate true}

        (let [strategy (first remaining-strategies)
              result ((:strategy/fn strategy))]

          (if (:repair/success result)
            result
            (recur (rest remaining-strategies) (inc iteration))))))))
```

**Exit Criteria:**

- [ ] Inner loop tries multiple repair strategies
- [ ] Agents can ask peer agents for help during repair
- [ ] Knowledge base consulted for similar past failures
- [ ] Escalates to human if all strategies fail
- [ ] Inner loop iterations logged in evidence

**Deliverable:** Robust inner loop with multi-strategy repair

### Week 3-4 Deliverables

**Enhanced Agent System:**

- ✅ Agents maintain memory and context across phases
- ✅ Agents communicate and coordinate
- ✅ Inner loop with multi-strategy repair
- ✅ Knowledge base integration for learning from past workflows
- ✅ Escalation mechanism when agents stuck

---

## Week 5-6: Self-Improvement Loop & Compliance Foundations (Feb 19 - Mar 5)

### Goal

Meta loop that learns from execution. Compliance-ready architecture.

### Meta Loop Implementation

**Current State:** Knowledge base exists, but may not have active meta loop

**Enhancement:** Signal extraction, heuristic evolution, A/B testing

#### Signal Extraction

```clojure
;; components/loop/src/ai/miniforge/loop/meta.clj

(ns ai.miniforge.loop.meta
  (:require
   [ai.miniforge.knowledge.interface :as kb]
   [ai.miniforge.artifact.interface :as artifact]))

(defn extract-signals-from-workflow
  "Extract learning signals from completed workflow"
  [workflow execution evidence-bundle]

  (let [signals []]

    ;; Performance signals
    (conj signals
          {:signal/type :performance
           :signal/metric :phase-duration
           :signal/phase :implement
           :signal/value (get-in evidence-bundle [:evidence/implement :phase/duration-ms])
           :signal/context {:workflow/type (:workflow/type workflow)}})

    ;; Quality signals
    (conj signals
          {:signal/type :quality
           :signal/metric :inner-loop-iterations
           :signal/phase :implement
           :signal/value (get-in evidence-bundle [:evidence/implement :phase/inner-loop-iterations])})

    ;; Success signals
    (conj signals
          {:signal/type :outcome
           :signal/metric :workflow-success
           :signal/value (get-in evidence-bundle [:evidence/outcome :outcome/success])})

    ;; Policy violation signals
    (when-let [violations (seq (mapcat :violations (:evidence/policy-checks evidence-bundle)))]
      (conj signals
            {:signal/type :policy
             :signal/metric :violations-count
             :signal/value (count violations)
             :signal/violations violations}))

    signals))

(defn update-knowledge-base
  "Update knowledge base with learnings"
  [kb workflow-id signals evidence-bundle]

  ;; Create learning unit
  (let [learning {:knowledge/type :learning
                  :knowledge/title (str "Workflow " workflow-id " execution")
                  :knowledge/content {:signals signals
                                     :evidence-summary (summarize-evidence evidence-bundle)}
                  :knowledge/links [{:link/to-type :pattern
                                    :link/rationale "Contributes to RDS import pattern"}]}]

    (kb/add-unit kb learning))

  ;; Update existing patterns
  (update-patterns-from-signals kb signals))

(defn evolve-heuristics
  "Evolve prompt heuristics based on signals"
  [heuristic-registry signals]

  ;; If implementer took many inner loop iterations, consider prompt improvement
  (when (some #(and (= :quality (:signal/type %))
                    (> (:signal/value %) 3))
              signals)

    ;; Propose new heuristic variant
    (let [current-heuristic (get-heuristic heuristic-registry :implementer :main-prompt)
          variant (create-variant current-heuristic
                                  {:improvement "Add more emphasis on constraint satisfaction"})]

      ;; A/B test the variant
      (add-heuristic-variant heuristic-registry :implementer variant))))
```

#### Heuristic A/B Testing

```clojure
;; components/heuristic/src/ai/miniforge/heuristic/evolution.clj

(ns ai.miniforge.heuristic.evolution)

(defn create-variant
  "Create a variant of existing heuristic"
  [base-heuristic improvement-description]
  {:heuristic/id (random-uuid)
   :heuristic/base-id (:heuristic/id base-heuristic)
   :heuristic/version (inc (:heuristic/version base-heuristic))
   :heuristic/content (apply-improvement (:heuristic/content base-heuristic)
                                         improvement-description)
   :heuristic/improvement-description improvement-description
   :heuristic/status :testing
   :heuristic/test-results []})

(defn select-heuristic
  "Select heuristic for workflow (A/B testing)"
  [heuristic-registry agent-type]

  (let [candidates (get-heuristic-candidates heuristic-registry agent-type)
        ;; Use epsilon-greedy: 90% best, 10% test variants
        epsilon 0.1]

    (if (< (rand) epsilon)
      ;; Explore: select variant with least data
      (select-least-tested candidates)
      ;; Exploit: select best performing
      (select-best-performing candidates))))

(defn record-heuristic-result
  "Record result of using a heuristic variant"
  [heuristic-registry heuristic-id workflow-id outcome signals]

  (let [result {:result/workflow-id workflow-id
                :result/outcome outcome
                :result/signals signals
                :result/timestamp (java.time.Instant/now)}]

    (update-heuristic heuristic-registry heuristic-id
                      (fn [h] (update h :heuristic/test-results conj result))))

  ;; If variant has enough data, promote or demote
  (evaluate-heuristic-promotion heuristic-registry heuristic-id))
```

**Exit Criteria:**

- [ ] Signals extracted from every workflow execution
- [ ] Knowledge base updated with learnings
- [ ] Heuristic variants created based on signals
- [ ] A/B testing infrastructure for heuristics
- [ ] Meta loop demonstrates improvement over time

**Deliverable:** Self-improving system that learns from execution

### Compliance Foundations (SOCII, FedRAMP)

**Objective:** Build architecture to support compliance certifications

#### Audit Trail

```clojure
;; components/logging/src/ai/miniforge/logging/audit.clj

(ns ai.miniforge.logging.audit
  (:require
   [ai.miniforge.logging.interface :as log]))

(defn audit-log
  "Write immutable audit log entry"
  [event-type actor resource action outcome metadata]

  (log/write-structured
   {:log/level :audit
    :audit/event-type event-type
    :audit/timestamp (java.time.Instant/now)
    :audit/actor actor  ; User or agent ID
    :audit/resource resource  ; Workflow, PR, artifact
    :audit/action action  ; :created, :modified, :approved, :merged
    :audit/outcome outcome  ; :success, :failure
    :audit/metadata metadata
    :audit/immutable true}))

;; Example audit logs

;; Workflow created
(audit-log :workflow-created
           {:user/id "chris@example.com"}
           {:workflow/id workflow-id}
           :created
           :success
           {:spec-file "specs/rds-import.edn"})

;; Agent action
(audit-log :agent-action
           {:agent/id :implementer :agent/instance instance-id}
           {:workflow/id workflow-id :phase :implement}
           :code-generated
           :success
           {:files-modified ["main.tf" "rds.tf"]
            :inner-loop-iteration 2})

;; Policy check
(audit-log :policy-check
           {:agent/id :implementer}
           {:artifact/id artifact-id}
           :checked
           :success
           {:policy-pack "terraform-aws"
            :violations []})

;; Human approval
(audit-log :human-approval
           {:user/id "chris@example.com"}
           {:pr/number 234}
           :approved
           :success
           {:approval-reason "Reviewed evidence bundle"})
```

#### Sensitive Data Handling

```clojure
;; components/artifact/src/ai/miniforge/artifact/sensitive.clj

(ns ai.miniforge.artifact.sensitive)

(defn scan-for-sensitive-data
  "Scan artifact for sensitive data before storing"
  [artifact]

  (let [content (artifact-content-as-string artifact)
        patterns [{:pattern #"[A-Z0-9]{20}" :type :aws-access-key}
                  {:pattern #"(?i)password\s*[:=]\s*\S+" :type :password}
                  {:pattern #"\d{3}-\d{2}-\d{4}" :type :ssn}]]

    (reduce (fn [findings pattern]
              (if-let [matches (re-seq (:pattern pattern) content)]
                (conj findings {:sensitive/type (:type pattern)
                               :sensitive/matches (count matches)
                               :sensitive/redacted true})
                findings))
            []
            patterns)))

(defn redact-sensitive-data
  "Redact sensitive data from artifact before storage"
  [artifact findings]
  ;; Replace sensitive data with [REDACTED:<type>]
  )

(defn store-with-compliance-metadata
  "Store artifact with compliance metadata"
  [store artifact compliance-tags]

  (artifact/store store
                  (assoc artifact
                         :compliance/tags compliance-tags
                         :compliance/sensitive-scan (scan-for-sensitive-data artifact)
                         :compliance/retention-policy :7-years
                         :compliance/encrypted true)))
```

**Exit Criteria:**

- [ ] Immutable audit logs for all actions (user and agent)
- [ ] Sensitive data scanning before artifact storage
- [ ] Compliance metadata on evidence bundles
- [ ] Retention policies configurable
- [ ] Documentation: "miniforge for SOCII compliance"

**Deliverable:** Compliance-ready architecture foundations

### Week 5-6 Deliverables

**Self-Improving System:**

- ✅ Meta loop extracts signals from every workflow
- ✅ Knowledge base grows with learnings
- ✅ Heuristic variants A/B tested
- ✅ System demonstrably improves over time

**Compliance Foundations:**

- ✅ Immutable audit trail
- ✅ Sensitive data scanning and redaction
- ✅ Compliance metadata on all artifacts
- ✅ Architecture ready for SOCII/FedRAMP

---

## Week 7: Local Fleet Operations Console (Mar 5 - Mar 12)

### Goal

Single-user operations console (TUI) to monitor the autonomous factory

**This is NOT the product. This is the monitoring interface.**

### TUI Design (K9s-inspired)

```
╭─────────────────────────────────────────────────────────────────────────────╮
│ miniforge local fleet  [Workflows: 5 | Agents: 4 | Active: 2]   ⟳ 15s ago  │
├─────────────────────────────────────────────────────────────────────────────┤
│ WORKFLOW                  STATUS       PHASE      PROGRESS       AGE        │
├─────────────────────────────────────────────────────────────────────────────┤
│ ● rds-import             executing    implement  ████▓▓▓▓▓▓ 40%  2h        │
│ ● k8s-migration          blocked      plan       ██▓▓▓▓▓▓▓▓ 20%  1d        │
│ ● vpc-update             completed    review     ██████████ 100% 4h        │
│ ● elasticache-import     pending      -          ▓▓▓▓▓▓▓▓▓▓ 0%   10m       │
│ ● lambda-deploy          executing    verify     ██████▓▓▓▓ 60%  30m       │
╰─────────────────────────────────────────────────────────────────────────────╯

[j/k] Navigate  [Enter] Details  [e] Evidence  [a] Artifacts  [q] Quit
```

### Implementation (Minimal for Week 7)

```
bases/local-fleet/
├── src/ai/miniforge/fleet/
│   ├── tui.clj              # Lanterna TUI
│   ├── views/
│   │   ├── workflows.clj    # Workflow list view
│   │   ├── detail.clj       # Workflow detail view
│   │   └── evidence.clj     # Evidence browser
│   └── api_client.clj       # Talks to local API
└── test/
```

**Focus on:**

1. Workflow list (show active/completed/failed)
2. Drill into workflow details (phases, agents, progress)
3. View evidence bundle
4. View artifacts with provenance

**Defer to post-OSS:**

- AI summaries
- Chat interface
- Batch operations
- Web dashboard

**Exit Criteria:**

- [ ] TUI shows live workflow list
- [ ] Can drill into workflow details
- [ ] Can view evidence bundle
- [ ] Can view artifacts with provenance
- [ ] Real-time updates (15s refresh)
- [ ] Vim-style keyboard navigation

**Deliverable:** `miniforge fleet watch` launches working TUI

---

## Week 8: OSS Packaging & Beta Launch (Mar 12 - Mar 19)

### Goal

Installable OSS release with documentation

### Packaging Tasks

#### 1. Polylith OSS Project

```clojure
;; projects/oss/deps.edn

{:deps {ai.miniforge/schema {:local/root "../../components/schema"}
        ai.miniforge/logging {:local/root "../../components/logging"}
        ai.miniforge/tool {:local/root "../../components/tool"}
        ai.miniforge/agent {:local/root "../../components/agent"}
        ai.miniforge/task {:local/root "../../components/task"}
        ai.miniforge/loop {:local/root "../../components/loop"}
        ai.miniforge/workflow {:local/root "../../components/workflow"}
        ai.miniforge/knowledge {:local/root "../../components/knowledge"}
        ai.miniforge/policy {:local/root "../../components/policy"}
        ai.miniforge/heuristic {:local/root "../../components/heuristic"}
        ai.miniforge/artifact {:local/root "../../components/artifact"}
        ai.miniforge/cli {:local/root "../../bases/cli"}
        ai.miniforge/local-fleet {:local/root "../../bases/local-fleet"}}

 :paths ["src" "resources"]

 :aliases {:uberjar {:extra-deps {uberdeps/uberdeps {:mvn/version "1.1.4"}}
                     :main-opts ["-m" "uberdeps.uberjar"
                                 "--target" "target/miniforge.jar"
                                 "--main-class" "ai.miniforge.cli.main"]}}}
```

#### 2. Babashka Build

```bash
# Build babashka uberscript for fast startup
bb build-uberscript
# -> dist/miniforge (single executable)

# Test
./dist/miniforge --version
./dist/miniforge workflow execute specs/test.edn
```

#### 3. Homebrew Formula

```ruby
# Formula/miniforge.rb

class Miniforge < Formula
  desc "Autonomous software factory - local execution"
  homepage "https://miniforge.ai"
  url "https://github.com/miniforge/miniforge/releases/download/v0.1.0/miniforge-0.1.0.tar.gz"
  sha256 "..."

  depends_on "openjdk@21" # For JVM-based components
  depends_on "babashka"   # For CLI

  def install
    libexec.install Dir["*"]
    bin.install_symlink libexec/"bin/miniforge"
  end

  test do
    system "#{bin}/miniforge", "--version"
  end
end
```

#### 4. Documentation

**Required docs:**

```
docs/
├── README.md                      # Project overview
├── GETTING_STARTED.md             # Installation & first workflow
├── CONCEPTS.md                    # Core concepts (agents, loops, evidence)
├── WORKFLOW_GUIDE.md              # Writing spec files
├── POLICY_AUTHORING.md            # Creating policy packs
├── COMPLIANCE.md                  # SOCII/FedRAMP foundations
├── EVIDENCE_BUNDLES.md            # Understanding provenance
└── examples/
    ├── rds-import.edn
    ├── k8s-deployment.edn
    ├── lambda-function.edn
    └── vpc-network-changes.edn
```

**Key message in README:**

> **miniforge is an autonomous software factory that runs on your local machine.**
>
> Write a specification → Agents execute the full SDLC autonomously → PR created with full evidence trail.
>
> **Core Differentiators:**
>
> - Autonomous multi-agent execution (Planner, Implementer, Tester, Reviewer)
> - Self-improving meta loop (learns from your workflows)
> - Full provenance & evidence bundles (trace back to original intent)
> - Semantic intent validation (declared intent must match implementation)
> - Compliance-ready architecture (SOCII, FedRAMP foundations)
> - Policy-as-code with gate validation

#### 5. Beta User Onboarding

**Target:** 10-20 beta users for Week 8

**Channels:**

- Direct outreach (platform engineers we know)
- HackerNews "Show HN: miniforge - autonomous software factory"
- r/devops, r/terraform
- Platform engineering Slack communities

**Onboarding flow:**

1. `brew install miniforge`
2. `miniforge init`
3. `miniforge workflow execute examples/rds-import.edn`
4. `miniforge fleet watch` (see it work)
5. Feedback form

### Week 8 Deliverables

**OSS Release:**

- ✅ Homebrew installable
- ✅ Docker image published
- ✅ Documentation complete
- ✅ 4 example spec files
- ✅ GitHub repo public

**Beta Launch:**

- ✅ 10+ beta users onboarded
- ✅ Show HN post
- ✅ Initial GitHub stars

---

## Post-OSS: Paid Feature Development (Week 9+)

**After OSS beta stable, build Paid features:**

1. **Multi-Repo Orchestration** (repo-dag, pr-train)
2. **Distributed Fleet** (fleet-daemon, operators-console)
3. **RBAC & Approvals** (authz)
4. **Org-wide Learning** (knowledge-shared, policy-distribution)
5. **Analytics & Telemetry** (telemetry, eval-harness)

**See PRODUCT_STRATEGY.md for Paid roadmap.**

---

## Success Metrics

### Week 1-2: Autonomous Execution

- [ ] Spec file → PR created autonomously
- [ ] All agents execute with context handoff
- [ ] Evidence bundle generated with provenance
- [ ] Semantic intent validation works
- [ ] Integration tests pass

### Week 3-4: Multi-Agent Coordination

- [ ] Agents maintain memory across phases
- [ ] Inter-agent communication logged
- [ ] Inner loop with multi-strategy repair
- [ ] Knowledge base integration working

### Week 5-6: Self-Improvement & Compliance

- [ ] Meta loop extracts signals from workflows
- [ ] Heuristic variants A/B tested
- [ ] Immutable audit trail for compliance
- [ ] Sensitive data scanning operational

### Week 7: Operations Console

- [ ] TUI shows live workflows
- [ ] Can view evidence bundles
- [ ] Artifact provenance queryable
- [ ] Real-time updates working

### Week 8: OSS Launch

- [ ] `brew install miniforge` works
- [ ] 10+ beta users onboarded
- [ ] Documentation complete
- [ ] Show HN post published
- [ ] First GitHub stars & feedback

---

## Implementation Strategy

### Resource Model

**You + Claude Max** building at 2-3K LOC/day sustained

**Leverage existing 37K LOC:** Most components exist, focus on integration & new features

### Dogfooding

**Starting Week 3:** Use miniforge to build miniforge

**Benefits:**

- Immediate validation
- Find UX issues fast
- Credibility (screenshots of self-building)

### Testing Strategy

- **Unit tests:** Per component (existing)
- **Integration tests:** Week 1-2 focus
- **Dogfooding:** Daily from Week 3
- **Beta user feedback:** Week 8

---

## Critical Path

**MUST HAVE for OSS Beta:**

1. ✅ Autonomous workflow execution (spec → PR)
2. ✅ Evidence bundles with provenance
3. ✅ Semantic intent validation
4. ✅ Policy gates
5. ✅ Basic operations console (TUI)
6. ✅ Documentation

**NICE TO HAVE (defer if needed):**

- AI summaries (can add post-beta)
- Web dashboard (TUI sufficient for beta)
- Advanced inner loop strategies
- Heuristic A/B testing (meta loop v1)

---

## Next Steps (This Week)

### Day 1 (Today): Autonomous Workflow Integration Test

```bash
cd /Users/chris/Local/miniforge.ai/miniforge
mkdir -p tests/integration
touch tests/integration/autonomous_workflow_test.clj

# Write test from Day 1-3 section above
# Goal: Spec → Planner → Implementer → Tester → Reviewer → PR → Evidence
```

### Day 2-3: Spec File Format & Validation

```bash
# Define spec schema
# Create spec validator
# Write example specs (RDS import, K8s deployment, VPC changes)
```

### Day 4-5: Agent Context Handoff

```bash
# Enhance agents to accept/produce rich context
# Test: Planner produces context, Implementer consumes
```

### Weekend: Evidence Bundle Generation

```bash
# Implement evidence bundle schema
# Generate bundles from workflow execution
# Test queryable provenance
```

---

**Ready to build the autonomous software factory?**

This plan focuses on what makes miniforge unique: autonomous execution, multi-agent cognition, self-improvement, full provenance, and compliance foundations. The operations console is just the window into watching it work.
