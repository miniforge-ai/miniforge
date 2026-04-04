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

(ns ai.miniforge.workflow.interface.protocols.workflow
  "Public protocol for governed workflow execution.
   This is the main extensibility point for custom workflow implementations.")

(defprotocol Workflow
  "Protocol for governed workflow execution.
   Manages state transitions through workflow phases."

  (start [this spec context]
    "Start a new workflow from a specification.
     Returns workflow-id.")

  (get-state [this workflow-id]
    "Get current workflow state.
     Returns {:phase :status :artifacts :errors :metrics}")

  (advance [this workflow-id phase-result]
    "Advance workflow based on phase execution result.
     phase-result: {:success? :artifacts :errors}
     Returns updated workflow state.")

  (rollback [this workflow-id target-phase reason]
    "Rollback to a previous phase.
     Returns updated workflow state.")

  (complete [this workflow-id]
    "Mark workflow as complete.
     Returns final workflow state.")

  (fail [this workflow-id error]
    "Mark workflow as failed.
     Returns final workflow state."))
