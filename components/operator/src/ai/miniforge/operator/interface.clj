;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.operator.interface
  "Public API for the operator (meta-agent) component.
   Manages the meta-loop: observe signals, detect patterns, propose improvements."
  (:require
   [ai.miniforge.operator.consumer :as consumer]
   [ai.miniforge.operator.core :as core]
   [ai.miniforge.operator.intervention :as intervention]
   [ai.miniforge.operator.protocol :as proto]))

;; ────────────────────────────────────────────────────────────────────────────
;; Protocol re-exports

(def Operator
  "Protocol satisfied by the meta-agent: observe signals, analyze patterns,
   propose/apply/reject improvements. Implement to provide an operator backend."
  proto/Operator)

(def PatternDetector
  "Protocol satisfied by pattern detectors: turn a sequence of signals into a
   sequence of detected patterns. Implement to provide custom detection."
  proto/PatternDetector)

(def ImprovementGenerator
  "Protocol satisfied by improvement generators: turn detected patterns plus
   context into a sequence of improvement proposals."
  proto/ImprovementGenerator)

(def Governance
  "Protocol satisfied by governance backends: decide whether an improvement
   needs human approval, may auto-apply, and resolve per-type approval policy."
  proto/Governance)

;; Type definitions
(def signal-types
  "Set of keywords naming the signal types the operator can observe
   (e.g. :workflow-complete, :workflow-failed, :phase-rollback)."
  proto/signal-types)

(def improvement-types
  "Set of keywords naming the improvement types the operator can propose
   (e.g. :prompt-change, :gate-adjustment, :policy-update)."
  proto/improvement-types)

(def intervention-types
  "Set of keywords naming the bounded v1 supervisory intervention vocabulary
   (e.g. :acknowledge, :retry, :cancel, :force-safe-mode)."
  intervention/intervention-types)

(def intervention-target-types
  "Set of keywords naming the recognized intervention target categories
   (e.g. :attention, :workflow, :policy-eval, :pr, :degradation, :supervision)."
  intervention/target-types)

(def intervention-lifecycle-states
  "Set of keywords naming all valid intervention lifecycle states, including
   the initial state, every transition endpoint, and the terminal states."
  intervention/lifecycle-states)

(def intervention-terminal-states
  "Set of keywords naming the terminal intervention lifecycle states:
   :rejected, :verified, :failed."
  intervention/terminal-states)

;; ────────────────────────────────────────────────────────────────────────────
;; Operator creation

(def create-operator
  "Create an operator (meta-agent).

   The operator observes workflow executions, detects patterns,
   and proposes process improvements.

   Options:
   - :knowledge-store - Knowledge store for rule storage
   - :config - Override default configuration

   Example:
     (create-operator)
     (create-operator {:knowledge-store k-store})"
  core/create-operator)

(def create-pattern-detector
  "Create a pattern detector.
   Detects patterns in workflow signals."
  core/create-pattern-detector)

(def create-improvement-generator
  "Create an improvement generator.
   Generates improvement proposals from detected patterns."
  core/create-improvement-generator)

(def create-governance
  "Create a governance manager.
   Controls improvement approval policies."
  core/create-governance)

;; ────────────────────────────────────────────────────────────────────────────
;; Bounded supervisory interventions

(def create-intervention
  "Create a bounded InterventionRequest map.

   Canonical, anomaly-returning entry point: returns the constructed
   intervention on success, or an `:invalid-input` anomaly when any
   step of the validation cascade rejects the request."
  intervention/create-intervention)

(def create-intervention!
  "Boundary variant of [[create-intervention]] that throws `ex-info`
   on validation failure. Prefer [[create-intervention]] in non-boundary
   code."
  intervention/create-intervention!)

(def approval-required?
  "Check whether an intervention type defaults to a pending-human step."
  intervention/approval-required?)

(def valid-intervention-type?
  "Check if an intervention type is part of the bounded vocabulary."
  intervention/valid-type?)

(def valid-intervention-target-type?
  "Check if an intervention target type is recognized."
  intervention/valid-target-type?)

(def valid-intervention-state?
  "Check if an intervention lifecycle state is valid."
  intervention/valid-state?)

(def intervention-terminal-state?
  "Check if an intervention lifecycle state is terminal."
  intervention/terminal-state?)

(def intervention-target-type
  "Resolve the default target type for an intervention."
  intervention/intervention-target-type)

(def valid-intervention-transition?
  "Check if an intervention lifecycle transition is valid."
  intervention/valid-transition?)

(def next-intervention-state
  "Resolve the next lifecycle state for an intervention transition."
  intervention/next-state)

(def transition-intervention
  "Apply a lifecycle transition to an intervention."
  intervention/transition)

(def start-intervention-approval
  "Move an intervention into :pending-human."
  intervention/start-approval)

(def approve-intervention
  "Approve an intervention."
  intervention/approve)

(def reject-intervention
  "Reject an intervention."
  intervention/reject)

(def dispatch-intervention
  "Mark an intervention as dispatched."
  intervention/dispatch)

(def apply-intervention-result
  "Mark an intervention as applied."
  intervention/apply-result)

(def verify-intervention
  "Mark an intervention as verified."
  intervention/verify)

(def fail-intervention
  "Mark an intervention as failed."
  intervention/fail)

(def supported-target-types
  "Return the supported target types for an intervention type."
  intervention/supported-target-types)

(def bounded-intervention-vocabulary?
  "Check if a set of intervention types is within the bounded vocabulary."
  intervention/bounded-vocabulary?)

;; ────────────────────────────────────────────────────────────────────────────
;; Operator-event consumer (Phase D D-2)

(def consume-operator-events!
  "Run one consumption pass over `{events-dir}/operator/`: parse
   intervention-requested events, validate, route through the
   lifecycle (auto-approving operator-driven request sources), publish
   every transition onto the stream, and advance the idempotency
   cursor. Options: :events-dir, :stream (required), :apply! (the D-3
   application hook). Returns {:routed n :skipped n :anomalies n}."
  consumer/consume-pass!)

(def start-operator-consumer!
  "Start a fixed-delay background poller running the consumer pass.
   Options are those of [[consume-operator-events!]] plus :interval-ms
   (default 1000). Returns a handle for [[stop-operator-consumer!]]."
  consumer/start!)

(def stop-operator-consumer!
  "Stop a poller started by [[start-operator-consumer!]]. Idempotent."
  consumer/stop!)

(def auto-approve-request-sources
  "Request sources whose interventions are auto-approved (the human IS
   the approver): the operator surfaces, not delegated agents."
  consumer/auto-approve-request-sources)

;; ────────────────────────────────────────────────────────────────────────────
;; Signal observation

(defn observe-signal
  "Record an observation signal.

   signal: {:type keyword :data map}

   Signal types:
   - :workflow-complete - Workflow finished successfully
   - :workflow-failed - Workflow failed
   - :phase-rollback - Phase was rolled back
   - :repeated-failure - Same failure occurred multiple times
   - :repair-pattern - Inner loop repair pattern detected
   - :human-override - Human overrode agent decision
   - :budget-exceeded - Budget limit exceeded
   - :quality-regression - Quality metrics decreased

   Returns the recorded signal with id and timestamp."
  [operator signal]
  (proto/observe-signal operator signal))

(defn get-signals
  "Query recorded signals.

   query:
   - :type - Filter by signal type
   - :since - Filter by timestamp (ms since epoch)
   - :limit - Max signals to return

   Returns sequence of signals, newest first."
  [operator query]
  (proto/get-signals operator query))

;; ────────────────────────────────────────────────────────────────────────────
;; Pattern analysis

(defn analyze-patterns
  "Analyze signals for patterns.

   Arguments:
   - operator - Operator instance
   - window-ms - Time window to analyze (ms)

   Returns:
     {:patterns [...] :recommendations [...]}

   Pattern types detected:
   - :repeated-phase-failure - Same phase failing multiple times
   - :frequent-rollback - Frequent rollbacks from a phase
   - :recurring-repair - Same repair pattern in inner loop"
  [operator window-ms]
  (proto/analyze-patterns operator window-ms))

(defn detect-patterns
  "Detect patterns in a sequence of signals.

   Arguments:
   - detector - PatternDetector instance
   - signals - Sequence of signals

   Returns sequence of detected patterns."
  [detector signals]
  (proto/detect detector signals))

(defn generate-improvements
  "Generate improvement proposals from patterns.

   Arguments:
   - generator - ImprovementGenerator instance
   - patterns - Detected patterns
   - context - Context map

   Returns sequence of improvement proposals."
  [generator patterns context]
  (proto/generate-improvements generator patterns context))

;; ────────────────────────────────────────────────────────────────────────────
;; Improvement management

(defn propose-improvement
  "Propose a process improvement.

   improvement:
   - :type - Improvement type keyword
   - :target - What to improve (phase, rule, etc.)
   - :change - Map describing the change
   - :rationale - Why this improvement

   Improvement types:
   - :prompt-change - Modify agent prompts
   - :gate-adjustment - Add/modify validation gates
   - :policy-update - Update policies
   - :rule-addition - Add knowledge rules
   - :budget-adjustment - Adjust budgets
   - :workflow-modification - Change workflow structure

   Returns {:proposal-id uuid :status keyword}"
  [operator improvement]
  (proto/propose-improvement operator improvement))

(defn get-proposals
  "Query improvement proposals.

   query:
   - :status - Filter by status (:proposed :approved :applied :rejected)
   - :type - Filter by improvement type

   Returns sequence of proposals, newest first."
  [operator query]
  (proto/get-proposals operator query))

(defn apply-improvement
  "Apply an approved improvement.

   Returns {:success? bool :applied improvement-map}"
  [operator proposal-id]
  (proto/apply-improvement operator proposal-id))

(defn reject-improvement
  "Reject an improvement proposal.

   Returns updated proposal with rejection reason."
  [operator proposal-id reason]
  (proto/reject-improvement operator proposal-id reason))

;; ────────────────────────────────────────────────────────────────────────────
;; Governance

(defn requires-approval?
  "Check if improvement requires human approval."
  [governance improvement]
  (proto/requires-approval? governance improvement))

(defn can-auto-apply?
  "Check if improvement can be applied automatically."
  [governance improvement]
  (proto/can-auto-apply? governance improvement))

(defn get-approval-policy
  "Get the approval policy for an improvement type.

   Returns:
     {:auto-approve? bool
      :required-confidence float
      :shadow-period-ms long}"
  [governance improvement-type]
  (proto/get-approval-policy governance improvement-type))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.workflow.interface :as wf])

  ;; Create operator
  (def op (create-operator))

  ;; Observe signals
  (observe-signal op {:type :workflow-failed
                      :data {:phase :implement
                             :error "Syntax error"}})

  ;; Get recent signals
  (get-signals op {:limit 10})

  ;; Analyze for patterns
  (analyze-patterns op (* 60 60 1000))

  ;; Propose improvement
  (propose-improvement op {:type :rule-addition
                           :target :knowledge-base
                           :change {:add-rule "Check syntax before implement"}
                           :rationale "Prevent syntax errors"})

  ;; Get proposals
  (get-proposals op {:status :proposed})

  ;; Integrate with workflow
  (def wf-mgr (wf/create-workflow))
  (wf/add-observer wf-mgr op)

  :end)
