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

(ns ai.miniforge.workflow.definition
  "Workflow-definition normalization for execution-machine compilation.

   Legacy configurable workflows still describe control flow via
   `:workflow/phases` + `:phase/next`. This namespace projects those legacy
   definitions into the canonical `:workflow/pipeline` form so registration,
   validation, and execution all compile the same FSM.")

;------------------------------------------------------------------------------ Layer 0
;; Legacy workflow helpers

(defn workflow-entry-phase
  "Resolve the entry phase for a workflow definition."
  [workflow]
  (or (:workflow/entry workflow)
      (:workflow/entry-phase workflow)
      (some-> workflow :workflow/phases first :phase/id)))

(defn find-phase
  "Find a legacy phase definition by id."
  [workflow phase-id]
  (first
   (filter #(= phase-id (:phase/id %))
           (:workflow/phases workflow))))

(defn legacy-transition-target
  "Return the first configured transition target for a legacy phase."
  [phase]
  (some-> phase :phase/next first :target))

(defn legacy-phase->pipeline-entry
  "Project a legacy phase definition into canonical pipeline form."
  [phase]
  (let [target (legacy-transition-target phase)
        terminal? (empty? (:phase/next phase))]
    (cond-> {:phase (:phase/id phase)
             :gates (get phase :phase/gates [])
             :terminal? terminal?}
      target (assoc :on-success target))))

;------------------------------------------------------------------------------ Layer 1
;; Pipeline normalization

(defn- append-unvisited-phase
  [ordered-ids phase-id]
  (if (contains? (set ordered-ids) phase-id)
    ordered-ids
    (conj ordered-ids phase-id)))

(defn- walk-primary-path
  [workflow phase-id visited]
  (if (or (nil? phase-id) (contains? visited phase-id))
    []
    (let [phase (find-phase workflow phase-id)
          next-target (legacy-transition-target phase)
          next-visited (conj visited phase-id)]
      (if phase
        (vec (cons phase-id
                   (walk-primary-path workflow next-target next-visited)))
        []))))

(defn legacy-phase-order
  "Compute stable execution order for legacy workflow phases.

   The primary path follows the declared entry phase and first transition
   target, matching the legacy configurable runner's semantics. Any remaining
   phases are appended in declaration order so registration and reachability
   checks still see the whole graph."
  [workflow]
  (let [phases (:workflow/phases workflow)
        entry-phase (workflow-entry-phase workflow)
        primary-order (walk-primary-path workflow entry-phase #{})]
    (reduce append-unvisited-phase
            primary-order
            (map :phase/id phases))))

(defn execution-pipeline
  "Return the canonical execution pipeline for a workflow."
  [workflow]
  (if-let [pipeline (:workflow/pipeline workflow)]
    pipeline
    (let [phase-order (legacy-phase-order workflow)]
      (->> phase-order
           (keep #(find-phase workflow %))
           (mapv legacy-phase->pipeline-entry)))))

(defn execution-workflow
  "Return a workflow map with authoritative `:workflow/pipeline`."
  [workflow]
  (assoc workflow :workflow/pipeline (execution-pipeline workflow)))

(def ensure-execution-pipeline
  "Backward-compatible alias for callers that need a workflow map with a
   canonical execution pipeline."
  execution-workflow)

;------------------------------------------------------------------------------ Rich Comment
(comment
  (execution-pipeline
   {:workflow/entry :plan
    :workflow/phases [{:phase/id :plan :phase/next [{:target :implement}]}
                      {:phase/id :implement :phase/next [{:target :done}]}
                      {:phase/id :done :phase/next []}]})
  ;; => [{:phase :plan :gates [] :on-success :implement}
  ;;     {:phase :implement :gates [] :on-success :done}
  ;;     {:phase :done :gates []}]

  :leave-this-here)
