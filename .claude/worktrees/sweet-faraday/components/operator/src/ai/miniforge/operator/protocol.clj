(ns ai.miniforge.operator.protocol
  "Operator protocols for meta-loop management.

   The Operator is the meta-agent that:
   - Observes workflow executions
   - Captures signals (failures, patterns, improvements)
   - Proposes process improvements
   - Manages the self-improvement loop")

;------------------------------------------------------------------------------ Layer 0
;; Signal types

(def signal-types
  "Types of signals the operator can observe."
  #{:workflow-complete
    :workflow-failed
    :phase-rollback
    :repeated-failure
    :repair-pattern
    :human-override
    :budget-exceeded
    :quality-regression})

(def improvement-types
  "Types of improvements the operator can propose."
  #{:prompt-change
    :gate-adjustment
    :policy-update
    :rule-addition
    :budget-adjustment
    :workflow-modification})

;------------------------------------------------------------------------------ Layer 1
;; Operator protocol

(defprotocol Operator
  "Protocol for the meta-agent that manages self-improvement."

  (observe-signal [this signal]
    "Record an observation signal.
     signal: {:type keyword :data map :timestamp long}")

  (get-signals [this query]
    "Query recorded signals.
     query: {:type keyword :since long :limit int}
     Returns sequence of signals.")

  (analyze-patterns [this window-ms]
    "Analyze signals for patterns.
     Returns {:patterns [...] :recommendations [...]}.")

  (propose-improvement [this improvement]
    "Propose a process improvement.
     improvement: {:type keyword :target keyword :change map :rationale string}
     Returns {:proposal-id uuid :status keyword}")

  (get-proposals [this query]
    "Query improvement proposals.
     Returns sequence of proposals.")

  (apply-improvement [this proposal-id]
    "Apply an approved improvement.
     Returns {:success? bool :applied improvement-map}")

  (reject-improvement [this proposal-id reason]
    "Reject an improvement proposal.
     Returns updated proposal."))

;------------------------------------------------------------------------------ Layer 2
;; Pattern detector protocol

(defprotocol PatternDetector
  "Protocol for detecting patterns in workflow signals."

  (detect [this signals]
    "Detect patterns in a sequence of signals.
     Returns sequence of detected patterns.")

  (get-pattern-types [this]
    "Get the pattern types this detector can identify."))

;------------------------------------------------------------------------------ Layer 3
;; Improvement generator protocol

(defprotocol ImprovementGenerator
  "Protocol for generating improvement proposals from patterns."

  (generate-improvements [this patterns context]
    "Generate improvement proposals from detected patterns.
     Returns sequence of improvement proposals.")

  (get-supported-patterns [this]
    "Get pattern types this generator can handle."))

;------------------------------------------------------------------------------ Layer 4
;; Governance protocol

(defprotocol Governance
  "Protocol for improvement governance.
   Controls what improvements can be applied automatically vs requiring approval."

  (requires-approval? [this improvement]
    "Check if improvement requires human approval.")

  (can-auto-apply? [this improvement]
    "Check if improvement can be applied automatically.")

  (get-approval-policy [this improvement-type]
    "Get the approval policy for an improvement type.
     Returns {:auto-approve? bool :required-confidence float :shadow-period-ms long}"))
