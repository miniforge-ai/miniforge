(ns ai.miniforge.operator.interface
  "Public API for the operator (meta-agent) component.
   Manages the meta-loop: observe signals, detect patterns, propose improvements."
  (:require
   [ai.miniforge.operator.core :as core]
   [ai.miniforge.operator.protocol :as proto]))

;------------------------------------------------------------------------------ Layer 0
;; Protocol re-exports

(def Operator proto/Operator)
(def PatternDetector proto/PatternDetector)
(def ImprovementGenerator proto/ImprovementGenerator)
(def Governance proto/Governance)

;; Type definitions
(def signal-types proto/signal-types)
(def improvement-types proto/improvement-types)

;------------------------------------------------------------------------------ Layer 1
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

;------------------------------------------------------------------------------ Layer 2
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

;------------------------------------------------------------------------------ Layer 3
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

;------------------------------------------------------------------------------ Layer 4
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

;------------------------------------------------------------------------------ Layer 5
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
