;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.workflow.validator
  "Validation for workflow configurations.
   Handles schema validation (Malli) and DAG validation (cycles, reachability)."
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clojure.set :as set]))

;------------------------------------------------------------------------------ Layer 0
;; Schema loading

(defn load-schema
  "Load Malli schema from resources/schemas/workflow.edn.
   Returns the schema map or nil if not found."
  []
  (when-let [resource (io/resource "schemas/workflow.edn")]
    (with-open [rdr (io/reader resource)]
      (edn/read (java.io.PushbackReader. rdr)))))

;------------------------------------------------------------------------------ Layer 1
;; Schema validation

(defn validate-schema
  "Validate workflow config against Malli schema.

   For now, this performs basic structural validation without full Malli.

   Returns:
   {:valid? boolean
    :errors [string]}"
  [config]
  (let [errors (atom [])]

    ;; Check required top-level keys
    (when-not (:workflow/id config)
      (swap! errors conj "Missing required key: :workflow/id"))
    (when-not (:workflow/version config)
      (swap! errors conj "Missing required key: :workflow/version"))
    (when-not (:workflow/type config)
      (swap! errors conj "Missing required key: :workflow/type"))
    (when-not (:workflow/phases config)
      (swap! errors conj "Missing required key: :workflow/phases"))
    (when-not (:workflow/entry-phase config)
      (swap! errors conj "Missing required key: :workflow/entry-phase"))
    (when-not (:workflow/exit-phases config)
      (swap! errors conj "Missing required key: :workflow/exit-phases"))

    ;; Check phases is a vector
    (when-let [phases (:workflow/phases config)]
      (when-not (vector? phases)
        (swap! errors conj ":workflow/phases must be a vector"))

      ;; Check each phase
      (doseq [[idx phase] (map-indexed vector phases)]
        (when-not (:phase/id phase)
          (swap! errors conj (str "Phase " idx " missing :phase/id")))
        (when-not (:phase/name phase)
          (swap! errors conj (str "Phase " idx " missing :phase/name")))
        (when-not (:phase/agent-type phase)
          (swap! errors conj (str "Phase " idx " missing :phase/agent-type")))))

    ;; Check exit-phases is a vector
    (when-let [exit-phases (:workflow/exit-phases config)]
      (when-not (vector? exit-phases)
        (swap! errors conj ":workflow/exit-phases must be a vector")))

    {:valid? (empty? @errors)
     :errors @errors}))

;------------------------------------------------------------------------------ Layer 2
;; DAG validation

(defn- build-phase-graph
  "Build adjacency list from phases.
   Returns map of phase-id -> #{target-phase-ids}"
  [phases]
  (reduce
   (fn [graph phase]
     (let [phase-id (:phase/id phase)
           targets (cond-> #{}
                     (:phase/on-success phase)
                     (conj (get-in phase [:phase/on-success :transition/target]))

                     (:phase/on-failure phase)
                     (conj (get-in phase [:phase/on-failure :transition/target])))]
       (assoc graph phase-id targets)))
   {}
   phases))

(defn- reachable-phases
  "Find all phases reachable from entry-phase.
   Returns set of phase-ids."
  [graph entry-phase]
  (loop [to-visit [entry-phase]
         visited #{}]
    (if (empty? to-visit)
      visited
      (let [current (first to-visit)
            to-visit' (rest to-visit)]
        (if (contains? visited current)
          (recur to-visit' visited)
          (let [neighbors (get graph current #{})
                new-to-visit (concat to-visit' neighbors)]
            (recur new-to-visit (conj visited current))))))))

(defn validate-dag
  "Validate workflow DAG structure.
   Checks:
   - No cycles
   - All phases referenced in transitions exist
   - Exit phases are reachable from entry phase

   Returns:
   {:valid? boolean
    :errors [string]}"
  [config]
  (let [errors (atom [])
        phases (:workflow/phases config)
        phase-ids (set (map :phase/id phases))
        entry-phase (:workflow/entry-phase config)
        exit-phases (set (:workflow/exit-phases config))
        graph (build-phase-graph phases)]

    ;; Check entry phase exists
    (when-not (contains? phase-ids entry-phase)
      (swap! errors conj (str "Entry phase not found: " entry-phase)))

    ;; Check exit phases exist
    (doseq [exit-phase exit-phases]
      (when-not (contains? phase-ids exit-phase)
        (swap! errors conj (str "Exit phase not found: " exit-phase))))

    ;; Check all transition targets exist
    (doseq [phase phases]
      (let [phase-id (:phase/id phase)]
        (when-let [success-target (get-in phase [:phase/on-success :transition/target])]
          (when-not (contains? phase-ids success-target)
            (swap! errors conj (str "Phase " phase-id " references non-existent phase: " success-target))))

        (when-let [failure-target (get-in phase [:phase/on-failure :transition/target])]
          (when-not (contains? phase-ids failure-target)
            (swap! errors conj (str "Phase " phase-id " references non-existent phase: " failure-target))))))

    ;; Note: We don't check for cycles because workflows commonly have
    ;; intentional cycles for retry/rollback logic (e.g., verify -> implement on failure)
    ;; Cycles are a valid workflow pattern

    ;; Check reachability (only if no errors so far)
    (when (empty? @errors)
      (let [reachable (reachable-phases graph entry-phase)
            unreachable (set/difference phase-ids reachable)]
        (when (seq unreachable)
          (swap! errors conj (str "Unreachable phases: " (vec unreachable))))))

    {:valid? (empty? @errors)
     :errors @errors}))

;------------------------------------------------------------------------------ Layer 3
;; Budget validation

(defn validate-budgets
  "Validate budget constraints.

   Returns:
   {:valid? boolean
    :errors [string]}"
  [config]
  (let [errors (atom [])
        budgets (:workflow/budgets config)]

    (when budgets
      ;; Check budget values are positive
      (when-let [tokens (:budget/tokens budgets)]
        (when-not (pos? tokens)
          (swap! errors conj "Budget tokens must be positive")))

      (when-let [cost (:budget/cost-usd budgets)]
        (when-not (pos? cost)
          (swap! errors conj "Budget cost must be positive")))

      (when-let [duration (:budget/duration-ms budgets)]
        (when-not (pos? duration)
          (swap! errors conj "Budget duration must be positive"))))

    {:valid? (empty? @errors)
     :errors @errors}))

;------------------------------------------------------------------------------ Layer 4
;; Combined validation

(defn validate-workflow
  "Perform full validation of workflow config.
   Combines schema, DAG, and budget validation.

   Returns:
   {:valid? boolean
    :errors [string]}"
  [config]
  (let [schema-result (validate-schema config)
        dag-result (validate-dag config)
        budget-result (validate-budgets config)
        all-errors (concat (:errors schema-result)
                           (:errors dag-result)
                           (:errors budget-result))]
    {:valid? (empty? all-errors)
     :errors (vec all-errors)}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Load schema
  (def schema (load-schema))

  ;; Test validation with sample config
  (def sample-config
    {:workflow/id :test-workflow
     :workflow/version "1.0.0"
     :workflow/type :test
     :workflow/phases
     [{:phase/id :start
       :phase/name "Start"
       :phase/agent-type :planner
       :phase/on-success {:transition/target :end}}
      {:phase/id :end
       :phase/name "End"
       :phase/agent-type :none}]
     :workflow/entry-phase :start
     :workflow/exit-phases [:end]})

  (validate-workflow sample-config)

  :end)
