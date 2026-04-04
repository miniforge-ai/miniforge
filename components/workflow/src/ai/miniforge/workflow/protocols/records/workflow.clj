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

(ns ai.miniforge.workflow.protocols.records.workflow
  "Record implementations for Workflow protocol."
  (:require
   [ai.miniforge.workflow.interface.protocols.workflow :as p]
   [ai.miniforge.workflow.protocols.impl.workflow :as impl]
   [ai.miniforge.logging.interface :as log]))

(defrecord SimpleWorkflow [config workflows observers logger]
  p/Workflow

  (start [_this spec context]
    (let [[workflow-id new-workflows] (impl/start-workflow-impl
                                       workflows observers logger spec context)]
      (reset! workflows new-workflows)
      workflow-id))

  (get-state [_this workflow-id]
    (impl/get-state-impl workflows workflow-id))

  (advance [_this workflow-id phase-result]
    (when-let [[new-state new-workflows] (impl/advance-impl
                                          workflows observers logger
                                          workflow-id phase-result)]
      (reset! workflows new-workflows)
      new-state))

  (rollback [_this workflow-id target-phase reason]
    (when-let [[new-state new-workflows] (impl/rollback-impl
                                          workflows observers logger config
                                          workflow-id target-phase reason)]
      (reset! workflows new-workflows)
      new-state))

  (complete [_this workflow-id]
    (when-let [[new-state new-workflows] (impl/complete-impl
                                          workflows observers logger
                                          workflow-id)]
      (reset! workflows new-workflows)
      new-state))

  (fail [_this workflow-id error]
    (when-let [[new-state new-workflows] (impl/fail-impl
                                          workflows observers logger
                                          workflow-id error)]
      (reset! workflows new-workflows)
      new-state)))

(defn create-simple-workflow
  "Create a SimpleWorkflow instance."
  ([] (create-simple-workflow {}))
  ([config]
   (let [default-config {:max-iterations-per-phase 5
                         :max-rollbacks 3
                         :timeout-per-phase-ms (* 10 60 1000)}]
     (->SimpleWorkflow
      (merge default-config config)
      (atom {})
      (atom [])
      (log/create-logger {:min-level :info})))))

(defn add-observer
  "Add a workflow observer."
  [workflow observer]
  (swap! (:observers workflow) conj observer)
  workflow)

(defn remove-observer
  "Remove a workflow observer."
  [workflow observer]
  (swap! (:observers workflow) (fn [obs] (remove #(= % observer) obs)))
  workflow)
