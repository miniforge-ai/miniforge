(ns ai.miniforge.decision.interface
  "Public API for canonical decision checkpoints and episodes."
  (:require
   [malli.core :as m]
   [malli.error :as me]
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

;------------------------------------------------------------------------------ Layer 1
;; Validation helpers

(defn valid?
  "Returns true if value validates against schema."
  [schema value]
  (m/validate schema value))

(defn validate
  "Returns value if valid, otherwise throws."
  [schema value]
  (if (m/validate schema value)
    value
    (throw (ex-info "Decision schema validation failed"
                    {:schema schema
                     :value value
                     :errors (me/humanize (m/explain schema value))}))))

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
