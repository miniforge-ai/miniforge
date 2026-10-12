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
(ns ai.miniforge.evidence-bundle.chain-evidence
  "Chain-level evidence aggregation.

   Aggregates per-step evidence bundles into a chain-level bundle,
   including chain definition, step results, total timing, and metrics."
  (:require [ai.miniforge.phase.interface :as phase]))

;------------------------------------------------------------------------------ Layer 0

;; Step Summarization
(defn ^{:stratum 0} summarize-step
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
  (cond-> {:step/id (:step/id step-result)
           :step/workflow-id (:step/workflow-id step-result)
           :step/index step-index
           :step/status (:step/status step-result)
           :step/has-output? (some? (:step/output step-result))}
    (contains? step-result :step/execution-id)
    (assoc :step/execution-id (:step/execution-id step-result))
    (contains? step-result :step/bindings)
    (assoc :step/bindings (:step/bindings step-result))))

(defn- ^{:stratum 0} definition-reference [definition result]
  (if (contains? result :chain/run-id)
    [(:chain/definition-id result) (:chain/definition-version result)]
    [(:chain/id definition) (:chain/version definition)]))

;; Metrics Aggregation
(defn ^{:stratum 0} aggregate-metrics
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
        completed (count (filter phase/succeeded? step-results))
        failed (count (filter phase/failed? step-results))
        with-output (count (filter #(some? (:step/output %)) step-results))]
    {:total-steps total
     :completed-steps completed
     :failed-steps failed
     :steps-with-output with-output}))

;; Chain Evidence Creation
(defn ^{:stratum 0} derive-chain-status
  "Derive chain evidence status from the chain result."
  [chain-result]
  (if (phase/succeeded? chain-result)
    :completed
    :failed))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} chain-identity [definition result]
  (let [[id version] (definition-reference definition result)
        retained (select-keys result [:chain/run-id :chain/definition-id :chain/definition-version
                                      :chain/definition-snapshot :chain/step-count :chain/edges])]
    (assoc retained :chain-evidence/chain-id id :chain-evidence/chain-version version)))

(defn ^{:stratum 1} build-step-summaries
  "Build step summaries from chain step results."
  [step-results]
  (vec (map-indexed (fn [idx sr] (summarize-step sr idx)) step-results)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} create-chain-evidence
  "Create a chain-level evidence record from chain execution results.

   Arguments:
   - chain-def: Chain definition map with :chain/id, :chain/steps, etc.
   - chain-result: Result map from run-chain with :chain/step-results, :chain/duration-ms.
     Admitted results retain canonical identity/snapshot; chain-def is used only for legacy identity.
   - opts: Optional map with :step-bundles (vector of per-step evidence bundle IDs)

   Returns: Chain evidence map with:
   - :chain-evidence/id (random UUID)
   - canonical :chain/* admission fields when present, plus compatible definition ID/version aliases
   - :chain-evidence/step-count (observed steps; :chain/step-count is the admitted definition total)
   - :chain-evidence/status (:completed or :failed)
   - :chain-evidence/duration-ms
   - :chain-evidence/step-summaries (vector of per-step summaries)
   - :chain-evidence/created-at"
  [chain-def chain-result & [opts]]
  (let [step-results (:chain/step-results chain-result)
        step-count (count step-results)
        status (derive-chain-status chain-result)
        summaries (build-step-summaries step-results)
        metrics (aggregate-metrics step-results)
        identity (chain-identity chain-def chain-result)]
    (cond-> (merge identity {:chain-evidence/id (random-uuid)
                            :chain-evidence/step-count step-count
                            :chain-evidence/status status
                            :chain-evidence/duration-ms (:chain/duration-ms chain-result)
                            :chain-evidence/step-summaries summaries
                            :chain-evidence/metrics metrics
                            :chain-evidence/created-at (java.time.Instant/now)})
      (:step-bundles opts)
      (assoc :chain-evidence/step-bundles (:step-bundles opts)))))

(comment
  (chain-identity {:chain/id :legacy :chain/version "1.0.0"} {}))
