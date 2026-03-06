(ns ai.miniforge.evidence-bundle.chain-evidence
  "Chain-level evidence aggregation.

   Aggregates per-step evidence bundles into a chain-level bundle,
   including chain definition, step results, total timing, and metrics."
  (:require [ai.miniforge.phase.registry :as phase-reg]))

;------------------------------------------------------------------------------ Layer 0
;; Step Summarization

(defn summarize-step
  "Create a summary of a single chain step for inclusion in chain evidence.

   Arguments:
   - step-result: Map with :step/id, :step/workflow-id, :step/status, :step/output
   - step-index: Zero-based index of this step in the chain

   Returns: Step summary map with:
   - :step/id
   - :step/workflow-id
   - :step/index
   - :step/status
   - :step/has-output? (boolean)"
  [step-result step-index]
  {:step/id (:step/id step-result)
   :step/workflow-id (:step/workflow-id step-result)
   :step/index step-index
   :step/status (:step/status step-result)
   :step/has-output? (some? (:step/output step-result))})

;------------------------------------------------------------------------------ Layer 1
;; Metrics Aggregation

(defn aggregate-metrics
  "Aggregate timing and count metrics across chain steps.

   Arguments:
   - step-results: Vector of step result maps

   Returns: Metrics map with:
   - :total-steps
   - :completed-steps
   - :failed-steps
   - :steps-with-output"
  [step-results]
  (let [total (count step-results)
        completed (count (filter phase-reg/succeeded? step-results))
        failed (count (filter phase-reg/failed? step-results))
        with-output (count (filter #(some? (:step/output %)) step-results))]
    {:total-steps total
     :completed-steps completed
     :failed-steps failed
     :steps-with-output with-output}))

;------------------------------------------------------------------------------ Layer 2
;; Chain Evidence Creation

(defn derive-chain-status
  "Derive chain evidence status from the chain result."
  [chain-result]
  (if (phase-reg/succeeded? chain-result)
    :completed
    :failed))

(defn build-step-summaries
  "Build step summaries from chain step results."
  [step-results]
  (vec (map-indexed (fn [idx sr] (summarize-step sr idx)) step-results)))

(defn create-chain-evidence
  "Create a chain-level evidence record from chain execution results.

   Arguments:
   - chain-def: Chain definition map with :chain/id, :chain/steps, etc.
   - chain-result: Result map from run-chain with :chain/step-results, :chain/duration-ms
   - opts: Optional map with :step-bundles (vector of per-step evidence bundle IDs)

   Returns: Chain evidence map with:
   - :chain-evidence/id (random UUID)
   - :chain-evidence/chain-id
   - :chain-evidence/chain-version
   - :chain-evidence/step-count
   - :chain-evidence/status (:completed or :failed)
   - :chain-evidence/duration-ms
   - :chain-evidence/step-summaries (vector of per-step summaries)
   - :chain-evidence/created-at"
  [chain-def chain-result & [opts]]
  (let [step-results (:chain/step-results chain-result)]
    (cond-> {:chain-evidence/id (random-uuid)
             :chain-evidence/chain-id (:chain/id chain-def)
             :chain-evidence/chain-version (:chain/version chain-def)
             :chain-evidence/step-count (count step-results)
             :chain-evidence/status (derive-chain-status chain-result)
             :chain-evidence/duration-ms (:chain/duration-ms chain-result)
             :chain-evidence/step-summaries (build-step-summaries step-results)
             :chain-evidence/metrics (aggregate-metrics step-results)
             :chain-evidence/created-at (java.time.Instant/now)}
      (:step-bundles opts)
      (assoc :chain-evidence/step-bundles (:step-bundles opts)))))
