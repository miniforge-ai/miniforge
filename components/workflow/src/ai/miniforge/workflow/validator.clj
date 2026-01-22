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
    (when-not (:workflow/name config)
      (swap! errors conj "Missing required key: :workflow/name"))
    (when-not (:workflow/description config)
      (swap! errors conj "Missing required key: :workflow/description"))
    (when-not (:workflow/created-at config)
      (swap! errors conj "Missing required key: :workflow/created-at"))
    (when-not (:workflow/task-types config)
      (swap! errors conj "Missing required key: :workflow/task-types"))
    (when-not (:workflow/phases config)
      (swap! errors conj "Missing required key: :workflow/phases"))

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
        (when-not (:phase/agent phase)
          (swap! errors conj (str "Phase " idx " missing :phase/agent")))
        (when-not (:phase/next phase)
          (swap! errors conj (str "Phase " idx " missing :phase/next")))))

    ;; Check task-types is a vector
    (when-let [task-types (:workflow/task-types config)]
      (when-not (vector? task-types)
        (swap! errors conj ":workflow/task-types must be a vector")))

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
           next-transitions (:phase/next phase [])
           targets (set (map :target next-transitions))]
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
   - At least one phase exists
   - All phases referenced in transitions exist
   - All phases are reachable from entry phase (first phase)

   Note: Cycles are allowed (for retry/rollback patterns)

   Returns:
   {:valid? boolean
    :errors [string]}"
  [config]
  (let [errors (atom [])
        phases (:workflow/phases config)
        phase-ids (set (map :phase/id phases))
        entry-phase (when (seq phases) (:phase/id (first phases)))
        graph (build-phase-graph phases)]

    ;; Check we have at least one phase
    (when (empty? phases)
      (swap! errors conj "Workflow must have at least one phase"))

    ;; Check all transition targets exist
    (doseq [phase phases]
      (let [phase-id (:phase/id phase)
            next-transitions (:phase/next phase [])]
        (doseq [transition next-transitions]
          (when-let [target (:target transition)]
            (when-not (contains? phase-ids target)
              (swap! errors conj (str "Phase " phase-id " references non-existent phase: " target)))))))

    ;; Note: We don't check for cycles because workflows commonly have
    ;; intentional cycles for retry/rollback logic (e.g., verify -> implement on failure)
    ;; Cycles are a valid workflow pattern

    ;; Check reachability (only if no errors so far)
    (when (and entry-phase (empty? @errors))
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
