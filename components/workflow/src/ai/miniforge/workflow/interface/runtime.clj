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

(ns ai.miniforge.workflow.interface.runtime
  "Workflow creation, lifecycle, and phase execution API."
  (:require
   [ai.miniforge.workflow.core :as core]
   [ai.miniforge.workflow.interface.protocols.phase-executor :as executor-proto]
   [ai.miniforge.workflow.interface.protocols.workflow :as workflow-proto]))

;------------------------------------------------------------------------------ Layer 0
;; Workflow lifecycle

(def create-workflow
  "Construct a new in-memory SimpleWorkflow instance (Workflow protocol).
   No args. Returns the workflow object used as `this` in the lifecycle fns."
  core/create-workflow)

(def add-observer
  "Register a WorkflowObserver on a workflow. Args: [workflow observer].
   Returns the workflow with the observer attached."
  core/add-observer)

(def remove-observer
  "Detach a previously-registered WorkflowObserver. Args: [workflow observer].
   Returns the workflow without that observer."
  core/remove-observer)

(def create-phase-executor
  "Build a PhaseExecutor for a phase keyword. Args: [phase llm-backend].
   Returns an executor for :plan/:implement/:verify/:release, or nil for
   phases with no dedicated executor."
  core/create-phase-executor)

(def run-workflow
  "Drive a run end-to-end from spec to a terminal state. Args:
   [workflow spec context]. Loops execute-phase/advance until the run is
   :completed or :failed. Returns the final state map."
  core/run-workflow)

(defn start
  [workflow spec context]
  (workflow-proto/start workflow spec context))

(defn get-state
  [workflow workflow-id]
  (workflow-proto/get-state workflow workflow-id))

(defn advance
  [workflow workflow-id phase-result]
  (workflow-proto/advance workflow workflow-id phase-result))

(defn rollback
  [workflow workflow-id target-phase reason]
  (workflow-proto/rollback workflow workflow-id target-phase reason))

(defn complete
  [workflow workflow-id]
  (workflow-proto/complete workflow workflow-id))

(defn fail
  [workflow workflow-id error]
  (workflow-proto/fail workflow workflow-id error))

(defn execute-phase
  [executor workflow-state context]
  (executor-proto/execute-phase executor workflow-state context))

(defn can-execute?
  [executor phase]
  (executor-proto/can-execute? executor phase))

(defn get-phase-requirements
  [executor phase]
  (executor-proto/get-phase-requirements executor phase))
