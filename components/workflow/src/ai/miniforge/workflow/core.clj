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

(ns ai.miniforge.workflow.core
  "Core workflow implementation.
   Manages SDLC phase execution: Plan → Design → Implement → Verify → Review → Release → Observe

   Note: Implementation has been moved to:
   - ai.miniforge.workflow.protocols.impl.workflow (workflow state management)
   - ai.miniforge.workflow.protocols.impl.phase-executor (phase execution logic)
   - ai.miniforge.workflow.protocols.records.workflow (SimpleWorkflow record)
   - ai.miniforge.workflow.protocols.records.phase-executor (phase executor records)

   This namespace remains for backward compatibility."
  (:require
   [ai.miniforge.workflow.interface.protocols.workflow :as p]
   [ai.miniforge.workflow.interface.protocols.phase-executor :as executor-proto]
   [ai.miniforge.workflow.protocols.impl.workflow :as workflow-impl]
   [ai.miniforge.workflow.protocols.records.workflow :as workflow-records]
   [ai.miniforge.workflow.protocols.records.phase-executor :as executor-records]
   [ai.miniforge.workflow.release :as release]))

;------------------------------------------------------------------------------ Layer 0
;; Re-exports from impl

(def create-workflow-state workflow-impl/create-workflow-state)
(def valid-transition? workflow-impl/valid-transition?)
(def phases workflow-impl/phases)
(def phase-transitions workflow-impl/phase-transitions)

;------------------------------------------------------------------------------ Layer 1
;; Re-exports from records

(def create-workflow workflow-records/create-simple-workflow)
(def add-observer workflow-records/add-observer)
(def remove-observer workflow-records/remove-observer)

;------------------------------------------------------------------------------ Layer 2
;; Phase executors

(defn create-phase-executor
  "Create a phase executor for a specific phase."
  [phase llm-backend]
  (case phase
    :plan (executor-records/create-plan-executor llm-backend)
    :implement (executor-records/create-implement-executor llm-backend)
    :verify (executor-records/create-verify-executor llm-backend)
    :release (release/->ReleasePhaseExecutor)
    nil))

;------------------------------------------------------------------------------ Layer 3
;; Workflow runner

(defn run-workflow
  "Run a complete workflow from spec to completion.

   Arguments:
   - workflow - Workflow instance
   - spec - Specification map {:title :description}
   - context - Execution context {:llm-backend :budget :tags}

   Returns final workflow state."
  [workflow spec context]
  (let [workflow-id (p/start workflow spec context)
        llm-backend (:llm-backend context)]

    (loop [state (p/get-state workflow workflow-id)]
      (let [current-phase (:workflow/phase state)]
        (cond
          (#{:done :completed :failed} (:workflow/status state))
          state

          (= current-phase :done)
          (p/complete workflow workflow-id)

          :else
          (if-let [executor (create-phase-executor current-phase llm-backend)]
            (let [result (executor-proto/execute-phase executor state context)
                  new-state (p/advance workflow workflow-id result)]
              (if (= :failed (:workflow/status new-state))
                new-state
                (recur new-state)))

            (let [new-state (p/advance workflow workflow-id
                                       {:success? true
                                        :artifacts []
                                        :errors []})]
              (recur new-state))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.llm.interface :as llm])

  (def wf (create-workflow))
  (def llm-client (llm/mock-client {:output "Mock plan response"}))

  (def result
    (run-workflow wf
                  {:title "Create greeting function"
                   :description "A function that says hello"}
                  {:llm-backend llm-client}))

  (:workflow/status result)
  (:workflow/phase result)
  (:workflow/artifacts result)

  :end)
