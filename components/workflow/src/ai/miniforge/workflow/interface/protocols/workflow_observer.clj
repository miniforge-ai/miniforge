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

(ns ai.miniforge.workflow.interface.protocols.workflow-observer
  "Public protocol for observing workflow events.
   This is the extensibility point for operator/meta-loop integration.")

(defprotocol WorkflowObserver
  "Protocol for observing workflow events.
   Used by the operator for meta-loop signals."

  (on-phase-start [this workflow-id phase context]
    "Called when a phase starts.")

  (on-phase-complete [this workflow-id phase result]
    "Called when a phase completes.")

  (on-phase-error [this workflow-id phase error]
    "Called when a phase errors.")

  (on-workflow-complete [this workflow-id final-state]
    "Called when entire workflow completes.")

  (on-rollback [this workflow-id from-phase to-phase reason]
    "Called when workflow rolls back."))
