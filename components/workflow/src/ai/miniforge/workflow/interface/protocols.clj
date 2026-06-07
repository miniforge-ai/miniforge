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

(ns ai.miniforge.workflow.interface.protocols
  "Workflow protocol and schema-adjacent re-exports."
  (:require
   [ai.miniforge.workflow.interface.protocols.phase-executor :as executor-proto]
   [ai.miniforge.workflow.interface.protocols.workflow :as workflow-proto]
   [ai.miniforge.workflow.interface.protocols.workflow-observer :as observer-proto]
   [ai.miniforge.workflow.protocols.impl.workflow :as workflow-impl]))

;------------------------------------------------------------------------------ Layer 0
;; Protocol re-exports

(def Workflow
  "Protocol governing workflow execution: start/get-state/advance/rollback/
   complete/fail. Main extensibility point for custom workflow
   implementations."
  workflow-proto/Workflow)

(def PhaseExecutor
  "Protocol for executing an individual SDLC phase: execute-phase/
   can-execute?/get-phase-requirements. Extensibility point for custom
   phase executors."
  executor-proto/PhaseExecutor)

(def WorkflowObserver
  "Protocol for observing workflow events (phase start/complete/error,
   workflow complete, rollback). Extensibility point for operator/
   meta-loop integration."
  observer-proto/WorkflowObserver)

(def phases
  "Ordered vector of SDLC phase keywords:
   [:spec :plan :design :implement :verify :review :release :observe :done]."
  workflow-impl/phases)

(def phase-transitions
  "Map of phase keyword -> set of allowed next-phase keywords. The empty
   set for :done marks the terminal phase."
  workflow-impl/phase-transitions)
