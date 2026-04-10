;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.training-capture.capture
  "Training record construction per learning.spec §2.1.

   Layer 0: Training record creation (pure)
   Layer 1: Training store (stateful)"
  (:require
   [ai.miniforge.training-capture.quality :as quality]))

;------------------------------------------------------------------------------ Layer 0
;; Training record construction

(defn create-training-record
  "Create a training record from agent execution results.

   Arguments:
     opts - map with keys:
       :agent-role      - keyword (:planner, :implementer, :tester, :reviewer, etc.)
       :workflow-id     - uuid
       :task-id         - uuid (optional)
       :phase           - keyword (:plan, :implement, :verify, :review, :release)
       :input           - map {:task :context :prompt-hash :artifacts-in}
       :output          - map {:artifacts-out :tool-calls :tokens-used :latency-ms}
       :feedback        - map {:validation-result :gates-passed :gates-failed
                               :repair-attempted? :repair-succeeded? :iterations-to-pass
                               :escalated? :human-override? :human-correction}
       :outcome         - map {:phase-succeeded? :workflow-succeeded? :production-incident?}

   Returns: complete training record with quality score and label."
  [{:keys [agent-role workflow-id task-id phase
           input output feedback outcome]}]
  (let [merged-feedback (merge feedback
                               (select-keys outcome [:phase-succeeded? :production-incident?]))
        quality-score (quality/compute-quality-score merged-feedback)
        example-label (quality/label-example quality-score feedback)]
    {:training/id (random-uuid)
     :training/timestamp (java.util.Date.)
     :training/agent-role agent-role
     :training/input (or input {})
     :training/output (or output {})
     :training/feedback (or feedback {})
     :training/outcome (or outcome {})
     :training/labels {:quality-score quality-score
                       :example-type example-label}
     :training/provenance {:workflow-id workflow-id
                           :task-id task-id
                           :phase phase}}))

;------------------------------------------------------------------------------ Layer 1
;; Training store (in-memory)

(defn create-store
  "Create an in-memory training example store.
   Returns: atom wrapping {:examples [] :by-workflow {} :by-agent {}}"
  []
  (atom {:examples []
         :by-workflow {}
         :by-agent {}}))

(defn- index-example
  "Pure state update: add example to store with workflow and agent indexes."
  [state example]
  (-> state
      (update :examples conj example)
      (update-in [:by-workflow (get-in example [:training/provenance :workflow-id])]
                 (fnil conj []) (:training/id example))
      (update-in [:by-agent (:training/agent-role example)]
                 (fnil conj []) (:training/id example))))

(defn store-example!
  "Store a training example in the store.
   Maintains indexes by workflow-id and agent-role."
  [store example]
  (swap! store index-example example)
  example)

(defn- matches-agent-role?
  "Returns true if the example matches the given agent role."
  [agent-role example]
  (= agent-role (:training/agent-role example)))

(defn- meets-quality-threshold?
  "Returns true if the example meets the minimum quality score."
  [min-quality example]
  (>= (get-in example [:training/labels :quality-score]) min-quality))

(defn- matches-label?
  "Returns true if the example matches the given label."
  [label example]
  (= label (get-in example [:training/labels :example-type])))

(defn get-examples
  "Get training examples with optional filters.

   Options:
     :agent-role   - keyword filter
     :min-quality  - float, minimum quality score
     :label        - keyword, example type filter
     :limit        - int, max results (default 100)"
  [store & [opts]]
  (let [{:keys [agent-role min-quality label limit]} opts]
    (cond->> (:examples @store)
      agent-role  (filter #(matches-agent-role? agent-role %))
      min-quality (filter #(meets-quality-threshold? min-quality %))
      label       (filter #(matches-label? label %))
      true (take (or limit 100))
      true vec)))

(defn example-count
  "Get the total number of stored examples."
  [store]
  (count (:examples @store)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def store (create-store))

  (def ex (create-training-record
           {:agent-role :implementer
            :workflow-id (random-uuid)
            :phase :implement
            :feedback {:validation-result :passed
                       :iterations-to-pass 2}
            :outcome {:phase-succeeded? true}}))

  (store-example! store ex)
  (example-count store)
  (get-examples store {:min-quality 0.8})

  :leave-this-here)
