(ns ai.miniforge.decision.interface
  "Public API for canonical decision checkpoints and episodes."
  (:require
   [ai.miniforge.decision.spec :as spec]
   [ai.miniforge.decision.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Schema re-exports

(def DecisionCheckpoint spec/DecisionCheckpoint)
(def DecisionEpisode spec/DecisionEpisode)

(def source-kinds spec/source-kinds)
(def authority-kinds spec/authority-kinds)
(def checkpoint-statuses spec/checkpoint-statuses)
(def episode-statuses spec/episode-statuses)
(def response-types spec/response-types)
(def risk-tiers spec/risk-tiers)
(def ControlPlaneContext spec/ControlPlaneContext)
(def LoopEscalationContext spec/LoopEscalationContext)
(def DecisionContext spec/DecisionContext)

;------------------------------------------------------------------------------ Layer 1
;; Validation helpers

(def valid? spec/valid?)
(def explain spec/explain)
(def validate spec/validate)

;------------------------------------------------------------------------------ Layer 2
;; Public constructors

(defn create-checkpoint
  "Create a canonical decision checkpoint."
  [opts]
  (validate DecisionCheckpoint
            (core/create-checkpoint opts)))

(defn resolve-checkpoint
  "Resolve a checkpoint with structured supervision."
  [checkpoint response]
  (validate DecisionCheckpoint
            (core/resolve-checkpoint checkpoint response)))

(defn create-episode
  "Create a decision episode shell from a checkpoint."
  [checkpoint]
  (validate DecisionEpisode
            (core/create-episode checkpoint)))

(defn update-episode
  "Update a decision episode from checkpoint state."
  ([episode checkpoint]
   (update-episode episode checkpoint nil))
  ([episode checkpoint opts]
   (validate DecisionEpisode
             (core/update-episode episode checkpoint opts))))

(defn create-control-plane-checkpoint
  "Normalize a control-plane decision request."
  [agent-id summary opts]
  (validate DecisionCheckpoint
            (core/create-control-plane-checkpoint agent-id summary opts)))

(defn create-loop-escalation-checkpoint
  "Normalize an inner-loop escalation."
  [loop-state opts]
  (validate DecisionCheckpoint
            (core/create-loop-escalation-checkpoint loop-state opts)))

(defn decision-response
  "Create a structured supervision response."
  [decision-type resolution & [rationale]]
  (validate spec/DecisionResponse
            (core/decision-response decision-type resolution rationale)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def checkpoint
    (create-control-plane-checkpoint
     (random-uuid)
     "Should I merge PR #42?"
     {:type :approval
      :priority :high
      :options ["yes" "no"]}))

  (def episode (create-episode checkpoint))

  (update-episode episode
                  (resolve-checkpoint checkpoint
                                      {:type :approve
                                       :value "yes"
                                       :authority-role :human}))

  :leave-this-here)
