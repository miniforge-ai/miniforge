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
(ns ai.miniforge.workflow.execution
  "Phase step execution: pick the active phase's interceptor, run it
   through enter -> gates -> leave, then either hand a plan to the DAG
   executor or apply the phase's transition.

   The pieces live in sibling namespaces, split out because this file
   held six strata (rule 210 allows three):
   - `execution-lifecycle`: enter -> gates -> leave, and recording the result
   - `execution-transition`: gate validation, result -> event, event -> machine
   - `execution-dag`: DAG activation after the plan phase
   - `execution-dag-sync`: copying sub-worktree changes into the parent
   - `execution-result-summary`: keys-only result summaries for events
   - `execution-events`: event publishing that never breaks execution"
  (:require [ai.miniforge.workflow.execution-dag :as execution-dag]
            [ai.miniforge.workflow.execution-lifecycle :as lifecycle]
            [ai.miniforge.workflow.execution-transition :as transition]
            [ai.miniforge.workflow.fsm :as workflow-fsm]
            [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

;; Phase step execution
(defn ^{:stratum 0} execute-phase-step
  "Execute a single phase step and return updated context.

   Arguments:
   - pipeline: Vector of interceptors
   - ctx: Current execution context
   - callbacks: Map with :on-phase-start, :on-phase-complete
   - merge-metrics-fn: Function to merge phase metrics
   - transition-to-completed-fn: Function to transition to completed state
   - transition-to-failed-fn: Function to transition to failed state

   Returns updated context."
  [pipeline ctx callbacks merge-metrics-fn transition-to-completed-fn transition-to-failed-fn]
  (let [machine-entry (when-let [machine (:execution/fsm-machine ctx)]
                        (workflow-fsm/machine-active-phase-entry machine
                                                                 (:execution/fsm-state ctx)))
        current-phase (:execution/current-phase ctx)
        interceptor (cond
                      machine-entry (get pipeline (:index machine-entry))
                      current-phase (some #(when (= current-phase
                                                   (get-in % [:config :phase]))
                                         %)
                                          pipeline)
                      :else nil)
        phase-name (get-in interceptor [:config :phase])
        {:keys [on-phase-start on-phase-complete]} callbacks
        ctx-with-phase (assoc ctx :execution/current-phase phase-name)]
    (if-not interceptor
      (let [phase-label (or current-phase
                            (:phase machine-entry)
                            :unknown)]
        (-> ctx
            (update :execution/errors conj
                    {:type :missing-current-phase
                     :phase phase-label
                     :message (messages/t :status/no-transition-defined
                                          {:state phase-label
                                           :event :phase/execute})})
            (transition-to-failed-fn)))
      (do
        ;; Notify phase start
        (when on-phase-start
          (on-phase-start ctx-with-phase interceptor))

        ;; Execute phase lifecycle: enter -> gates -> leave
        (let [[ctx-after-lifecycle phase-result] (lifecycle/execute-phase-lifecycle interceptor ctx-with-phase)
              ;; Process result: response chain + metrics + files + artifacts
              ctx-processed (lifecycle/process-phase-result ctx-after-lifecycle phase-name phase-result merge-metrics-fn)]

          ;; Notify phase complete
          (when on-phase-complete
            (on-phase-complete ctx-processed interceptor phase-result))

          ;; After plan phase, attempt DAG parallelization before normal transition
          (or (execution-dag/try-dag-execution ctx-processed phase-name phase-result pipeline
                                               transition-to-completed-fn transition-to-failed-fn)
              ;; Normal transition (non-plan phases, or plan not parallelizable)
              (let [event (transition/determine-phase-event (:config interceptor)
                                                            phase-result)]
                (transition/apply-phase-transition ctx-processed event pipeline
                                                   transition-to-completed-fn
                                                   transition-to-failed-fn))))))))
